package com.nms.server.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The range parser decides which addresses get probed, so its mistakes are
 * either a scan that misses devices or one that touches addresses it was not
 * asked to.
 */
class IpRangeTest {

    private static List<String> addressesOf(String specification) {
        List<String> found = new ArrayList<>();
        IpRange.parse(specification).addresses().forEach(found::add);
        return found;
    }

    @Test
    void parsesASingleAddress() {
        assertThat(addressesOf("10.0.0.5")).containsExactly("10.0.0.5");
    }

    @Test
    void parsesALastOctetRange() {
        assertThat(addressesOf("10.0.0.1-4"))
                .containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4");
    }

    @Test
    void parsesARangeSpanningOctets() {
        assertThat(addressesOf("10.0.0.254-10.0.1.2"))
                .containsExactly("10.0.0.254", "10.0.0.255", "10.0.1.0", "10.0.1.1", "10.0.1.2");
    }

    /**
     * Probing the network and broadcast addresses finds nothing, and a
     * directed broadcast can provoke a reply from every host on the segment
     * at once -- which looks like one device answering from many addresses.
     */
    @Test
    @DisplayName("CIDR omits the network and broadcast addresses")
    void cidrOmitsNetworkAndBroadcast() {
        List<String> addresses = addressesOf("192.168.1.0/29");

        assertThat(addresses).containsExactly(
                "192.168.1.1", "192.168.1.2", "192.168.1.3",
                "192.168.1.4", "192.168.1.5", "192.168.1.6");
        assertThat(addresses).doesNotContain("192.168.1.0", "192.168.1.7");
    }

    /**
     * A /31 is a point-to-point link and a /32 is a single host. Neither has
     * a network or broadcast address to skip, and applying the /24 rule to
     * them yields an empty scan -- silently monitoring nothing.
     */
    @Test
    void slash31AndSlash32AreFullyUsable() {
        assertThat(addressesOf("10.0.0.4/31")).containsExactly("10.0.0.4", "10.0.0.5");
        assertThat(addressesOf("10.0.0.9/32")).containsExactly("10.0.0.9");
    }

    @Test
    @DisplayName("CIDR host bits are ignored rather than rejected")
    void cidrAcceptsAnAddressInsideTheNetwork() {
        // 10.0.0.7/24 unambiguously means the 10.0.0.0/24 network. Refusing
        // it would be pedantry.
        assertThat(addressesOf("10.0.0.7/24")).hasSize(254).contains("10.0.0.1", "10.0.0.254");
    }

    @Test
    void parsesSeveralCommaSeparatedBlocks() {
        List<String> addresses = addressesOf("10.0.0.1-2, 192.168.5.7 , 172.16.0.0/30");

        assertThat(addresses).containsExactly(
                "10.0.0.1", "10.0.0.2",
                "192.168.5.7",
                "172.16.0.1", "172.16.0.2");
    }

    @Test
    void reportsTheTotalSizeWithoutExpanding() {
        assertThat(IpRange.parse("10.0.0.0/24").size()).isEqualTo(254);
        assertThat(IpRange.parse("10.0.0.0/24, 10.0.1.0/24").size()).isEqualTo(508);
    }

    /**
     * A /8 at a hundred addresses a second takes two days. Accepting it would
     * produce a rule that never finishes and occupies a scan slot forever, so
     * it is refused at the point the operator can still fix it.
     */
    @Test
    void refusesARangeTooLargeToFinish() {
        assertThatThrownBy(() -> IpRange.parse("10.0.0.0/8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Split it into several rules");
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> IpRange.parse("not-an-address"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpRange.parse("10.0.0.300"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside 0-255");
        assertThatThrownBy(() -> IpRange.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-32");
        assertThatThrownBy(() -> IpRange.parse("10.0.0.50-10"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ends before it starts");
        assertThatThrownBy(() -> IpRange.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The addresses are generated as they are consumed, so a large range can
     * be iterated without ever holding it in memory.
     */
    @Test
    void generatesLazilyRatherThanMaterialising() {
        IpRange range = IpRange.parse("10.0.0.0/16");

        assertThat(range.size()).isEqualTo(65_534);

        int taken = 0;
        for (String address : range.addresses()) {
            assertThat(address).startsWith("10.0.");
            if (++taken == 5) {
                break;
            }
        }
        assertThat(taken).isEqualTo(5);
    }
}
