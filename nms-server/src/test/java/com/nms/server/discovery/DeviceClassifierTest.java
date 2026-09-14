package com.nms.server.discovery;

import com.nms.server.discovery.DeviceClassifier.Classification;
import com.nms.server.domain.HostClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier's output is a suggestion a person confirms, so being wrong
 * is recoverable -- but being wrong in a way that reads as confident is not.
 * These cover the cases where the evidence genuinely decides, and the ones
 * where it only hints.
 */
class DeviceClassifierTest {

    private final DeviceClassifier classifier = new DeviceClassifier();

    @Test
    @DisplayName("ONVIF is conclusive: nothing but a camera implements it")
    void onvifMeansCamera() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.ONVIF_INFO, "HIKVISION DS-2CD2143G0-I"));

        assertThat(result.hostClass()).isEqualTo(HostClass.CAMERA);
        assertThat(result.suggestedTemplate()).isEqualTo("Template: IP camera");
        assertThat(result.suggestedName()).isEqualTo("HIKVISION DS-2CD2143G0-I");
        assertThat(result.reason()).contains("ONVIF");
    }

    @Test
    void openRtspPortMeansCamera() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.OPEN_PORTS, "80,554"));

        assertThat(result.hostClass()).isEqualTo(HostClass.CAMERA);
        assertThat(result.suggestedTemplate()).isEqualTo("Template: IP camera");
        assertThat(result.reason()).contains("554");
    }

    @Test
    void recognisesACameraFromItsSnmpDescription() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "AXIS P3245-LVE Network Camera",
                DeviceClassifier.SNMP_NAME, "cam-carpark"));

        assertThat(result.hostClass()).isEqualTo(HostClass.CAMERA);
        assertThat(result.suggestedName()).isEqualTo("cam-carpark");
    }

    @Test
    void recognisesNetworkEquipment() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION,
                "Cisco IOS Software, C2960X Software, Version 15.2(4)E10"));

        assertThat(result.hostClass()).isEqualTo(HostClass.NETWORK_DEVICE);
        assertThat(result.suggestedTemplate()).isEqualTo("Template: SNMP network device");
    }

    @Test
    void matchesVendorStringsRegardlessOfCase() {
        assertThat(classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "HIKVISION IPCAM")).hostClass())
                .isEqualTo(HostClass.CAMERA);
        assertThat(classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "MikroTik RouterOS")).hostClass())
                .isEqualTo(HostClass.NETWORK_DEVICE);
    }

    @Test
    void separatesPrintersAndUpsFromNetworkEquipment() {
        assertThat(classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "HP LaserJet M507")).hostClass())
                .isEqualTo(HostClass.PRINTER);
        assertThat(classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "APC Smart-UPS 1500")).hostClass())
                .isEqualTo(HostClass.UPS);
    }

    /**
     * On a typical estate the things that answer SNMP at all are the network
     * equipment, so an unrecognised vendor string is still a better guess
     * than "unknown".
     */
    @Test
    void treatsUnrecognisedSnmpAsNetworkEquipment() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.SNMP_DESCRIPTION, "Some Vendor Appliance 9000"));

        assertThat(result.hostClass()).isEqualTo(HostClass.NETWORK_DEVICE);
        assertThat(result.reason()).isEqualTo("answered SNMP");
    }

    /**
     * No agent template is offered for a bare server. The agent has to be
     * installed on the machine first, and a template whose every item sits
     * unsupported until then is worse than no template.
     */
    @Test
    void suggestsOnlyReachabilityForAServerWithNoAgent() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.OPEN_PORTS, "22,80"));

        assertThat(result.hostClass()).isEqualTo(HostClass.SERVER);
        assertThat(result.suggestedTemplate()).isEqualTo("Template: ICMP reachability");
    }

    @Test
    void fallsBackToReachabilityWhenOnlyPingAnswered() {
        Classification result = classifier.classify(Map.of());

        assertThat(result.hostClass()).isEqualTo(HostClass.GENERIC);
        assertThat(result.suggestedTemplate()).isEqualTo("Template: ICMP reachability");
        assertThat(result.reason()).contains("ping");
    }

    @Test
    void survivesAMalformedPortList() {
        Classification result = classifier.classify(Map.of(
                DeviceClassifier.OPEN_PORTS, "80, , not-a-port, 554"));

        // The unparseable entries are skipped rather than discarding the
        // whole list, so 554 is still seen.
        assertThat(result.hostClass()).isEqualTo(HostClass.CAMERA);
    }

    @Test
    void neverReturnsNullClassOrReason() {
        for (Map<String, String> input : java.util.List.of(
                Map.<String, String>of(),
                Map.of(DeviceClassifier.OPEN_PORTS, ""),
                Map.of(DeviceClassifier.SNMP_DESCRIPTION, ""),
                Map.of(DeviceClassifier.ONVIF_INFO, ""))) {
            Classification result = classifier.classify(input);
            assertThat(result.hostClass()).isNotNull();
            assertThat(result.reason()).isNotBlank();
        }
    }
}
