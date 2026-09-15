package com.nms.server.service;

import com.nms.server.api.dto.HostDtos.HostRequest;
import com.nms.server.api.dto.HostDtos.InterfaceRequest;
import com.nms.server.domain.Host;
import com.nms.server.domain.HostFlags;
import com.nms.server.domain.HostInterface;
import com.nms.server.domain.InterfaceType;
import com.nms.server.domain.SnmpVersion;
import com.nms.server.repository.CoreRepositories.HostGroupRepository;
import com.nms.server.repository.CoreRepositories.ProxyRepository;
import com.nms.server.repository.HostRepository;
import com.nms.server.repository.ItemRepository;
import com.nms.server.repository.ProblemRepository;
import com.nms.server.repository.TriggerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Editing a host must not destroy the credentials that reach it.
 *
 * <p>Reading a host deliberately omits its SNMP community string and v3
 * passphrases, so no client can send them back. Treating the resulting null as
 * "clear it" meant that correcting a switch's address through the API stopped
 * its SNMP collection outright: the host stayed in the interface, its items
 * stayed enabled, and every one of them failed authentication from then on.
 * Nothing on any screen said the community string had been erased, and the
 * operator who made the edit had no reason to think they had changed it.
 */
class HostInterfaceCredentialTest {

    private static final long TENANT = 1L;
    private static final long HOST = 5L;

    private final HostRepository hosts = mock(HostRepository.class);
    private final ProblemRepository problems = mock(ProblemRepository.class);

    private final HostService service = new HostService(
            hosts,
            mock(HostGroupRepository.class),
            mock(ProxyRepository.class),
            mock(ItemRepository.class),
            mock(TriggerRepository.class),
            problems,
            mock(TemplateLinker.class));

    @Test
    @DisplayName("an omitted community string keeps the stored one")
    void anOmittedCommunityIsKept() {
        Host host = hostWithSnmpInterface(7L, "10.30.1.1", "s3cr3t-community");

        service.update(TENANT, HOST, snmpRequest(7L, "10.30.1.2", null));

        assertThat(only(host).getIp()).isEqualTo("10.30.1.2");
        assertThat(only(host).getSnmpCommunity()).isEqualTo("s3cr3t-community");
    }

    /**
     * Every update replaces the interface rows, so the identifiers change. A
     * client holding a response it read a minute ago sends one that no longer
     * exists -- and matching on the identifier alone would lose exactly the
     * credential this is meant to preserve.
     */
    @Test
    @DisplayName("a stale or absent identifier still matches the one interface of that type")
    void matchesOnTypeWhenTheIdentifierHasMovedOn() {
        Host host = hostWithSnmpInterface(7L, "10.30.1.1", "s3cr3t-community");

        service.update(TENANT, HOST, snmpRequest(999L, "10.30.1.2", null));

        assertThat(only(host).getSnmpCommunity()).isEqualTo("s3cr3t-community");
    }

    @Test
    void anEmptyStringClearsItDeliberately() {
        Host host = hostWithSnmpInterface(7L, "10.30.1.1", "s3cr3t-community");

        service.update(TENANT, HOST, snmpRequest(7L, "10.30.1.1", ""));

        assertThat(only(host).getSnmpCommunity()).isEmpty();
    }

    @Test
    void asubmittedCommunityReplacesTheStoredOne() {
        Host host = hostWithSnmpInterface(7L, "10.30.1.1", "old-community");

        service.update(TENANT, HOST, snmpRequest(7L, "10.30.1.1", "new-community"));

        assertThat(only(host).getSnmpCommunity()).isEqualTo("new-community");
    }

    /**
     * Two interfaces of one type cannot be told apart without an identifier,
     * so nothing is carried over rather than guessing and attaching one
     * device's credentials to another.
     */
    @Test
    void doesNotGuessBetweenTwoInterfacesOfTheSameType() {
        Host host = host();
        host.getInterfaces().add(snmpInterface(host, 7L, "10.30.1.1", "first-community"));
        host.getInterfaces().add(snmpInterface(host, 8L, "10.30.1.2", "second-community"));
        stub(host);

        service.update(TENANT, HOST, snmpRequest(null, "10.30.1.3", null));

        assertThat(only(host).getSnmpCommunity()).isNull();
    }

    private static HostInterface only(Host host) {
        assertThat(host.getInterfaces()).hasSize(1);
        return host.getInterfaces().get(0);
    }

    private Host hostWithSnmpInterface(long interfaceId, String ip, String community) {
        Host host = host();
        host.getInterfaces().add(snmpInterface(host, interfaceId, ip, community));
        stub(host);
        return host;
    }

    private void stub(Host host) {
        when(hosts.findByIdWithDetails(HOST)).thenReturn(Optional.of(host));
        when(problems.findOpenByHostId(anyLong())).thenReturn(List.of());
    }

    private static Host host() {
        Host host = new Host();
        host.setId(HOST);
        host.setTenantId(TENANT);
        host.setTechnicalName("sw-core-01");
        host.setName("Core switch");
        host.setFlags(HostFlags.MONITORED);
        return host;
    }

    private static HostInterface snmpInterface(Host host, long id, String ip, String community) {
        HostInterface hostInterface = new HostInterface();
        hostInterface.setId(id);
        hostInterface.setHost(host);
        hostInterface.setType(InterfaceType.SNMP);
        hostInterface.setMain(true);
        hostInterface.setUseIp(true);
        hostInterface.setIp(ip);
        hostInterface.setPort(161);
        hostInterface.setSnmpVersion(SnmpVersion.V2C);
        hostInterface.setSnmpCommunity(community);
        return hostInterface;
    }

    private static HostRequest snmpRequest(Long interfaceId, String ip, String community) {
        return new HostRequest(
                "sw-core-01", "Core switch", com.nms.server.domain.HostClass.NETWORK_DEVICE,
                null, null, null, null, null,
                List.of(new InterfaceRequest(
                        interfaceId, InterfaceType.SNMP, true, true, ip, null, 161,
                        SnmpVersion.V2C, community,
                        null, null, null, null, null, null)),
                null, null);
    }
}
