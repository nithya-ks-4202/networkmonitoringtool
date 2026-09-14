package com.nms.collector.snmp;

import com.nms.common.CheckRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the process-wide SNMP transport and builds per-request targets.
 *
 * <p>One {@link Snmp} instance serves every poller thread. SNMP is a
 * request/response protocol over a single UDP socket, and snmp4j multiplexes
 * concurrent requests over it by request ID -- creating a session per check
 * would burn a file descriptor and an engine-discovery round trip on every
 * poll, which at thousands of items per minute is ruinous.
 */
@Component
public class SnmpSession {

    private static final Logger log = LoggerFactory.getLogger(SnmpSession.class);

    private final Snmp snmp;

    /**
     * v3 users already registered with the USM. Re-adding a user on every poll
     * is wasteful, and snmp4j keys users by name, so a set of names is enough
     * to make registration idempotent.
     */
    private final Set<String> registeredUsers = ConcurrentHashMap.newKeySet();

    public SnmpSession() {
        try {
            DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
            this.snmp = new Snmp(transport);

            // The USM must exist before any v3 target is built, and the local
            // engine ID must be stable for the lifetime of the process or
            // every authenticated request triggers re-discovery.
            SecurityProtocols.getInstance().addDefaultProtocols();
            USM usm = new USM(SecurityProtocols.getInstance(),
                    new OctetString(MPv3.createLocalEngineID()), 0);
            SecurityModels.getInstance().addSecurityModel(usm);

            transport.listen();
            log.info("SNMP transport listening on {}", transport.getListenAddress());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open the SNMP transport", e);
        }
    }

    public Snmp snmp() {
        return snmp;
    }

    /**
     * Builds the snmp4j target describing how to reach this request's device.
     *
     * @throws IllegalArgumentException when the credentials are incomplete,
     *                                  which is a configuration error rather
     *                                  than a device failure
     */
    public Target<?> targetFor(CheckRequest request) {
        int port = request.intParam("snmpPort", request.port() > 0 ? request.port() : 161);
        Address address = GenericAddress.parse("udp:" + request.address() + "/" + port);
        if (address == null) {
            throw new IllegalArgumentException("Unparseable SNMP address: " + request.address());
        }

        String version = request.param("snmpVersion", "V2C").toUpperCase(Locale.ROOT);
        int retries = request.intParam("snmpRetries", 1);
        long timeoutMillis = request.timeout().toMillis();

        if ("V3".equals(version)) {
            return userTarget(request, address, retries, timeoutMillis);
        }
        return communityTarget(request, address, version, retries, timeoutMillis);
    }

    private Target<?> communityTarget(CheckRequest request, Address address, String version,
                                      int retries, long timeoutMillis) {
        String community = request.param("snmpCommunity", "public");

        CommunityTarget<Address> target = new CommunityTarget<>();
        target.setAddress(address);
        target.setCommunity(new OctetString(community));
        target.setVersion("V1".equals(version) ? SnmpConstants.version1 : SnmpConstants.version2c);
        target.setRetries(retries);
        target.setTimeout(timeoutMillis);
        return target;
    }

    private Target<?> userTarget(CheckRequest request, Address address,
                                 int retries, long timeoutMillis) {
        String securityName = request.param("snmpSecurityName", "");
        if (securityName.isEmpty()) {
            throw new IllegalArgumentException("SNMPv3 requires a security name");
        }

        String levelName = request.param("snmpSecurityLevel", "NO_AUTH_NO_PRIV")
                .toUpperCase(Locale.ROOT);
        int securityLevel = switch (levelName) {
            case "AUTH_NO_PRIV" -> SecurityLevel.AUTH_NOPRIV;
            case "AUTH_PRIV" -> SecurityLevel.AUTH_PRIV;
            default -> SecurityLevel.NOAUTH_NOPRIV;
        };

        registerUser(request, securityName, securityLevel);

        UserTarget<Address> target = new UserTarget<>();
        target.setAddress(address);
        target.setVersion(SnmpConstants.version3);
        target.setSecurityName(new OctetString(securityName));
        target.setSecurityLevel(securityLevel);
        target.setSecurityModel(org.snmp4j.security.SecurityModel.SECURITY_MODEL_USM);
        target.setRetries(retries);
        target.setTimeout(timeoutMillis);

        String contextName = request.param("snmpContextName", "");
        if (!contextName.isEmpty()) {
            // Context lives on the PDU for v3, but snmp4j reads it from the
            // target when building the scoped PDU.
            target.setSecurityName(new OctetString(securityName));
        }
        return target;
    }

    private void registerUser(CheckRequest request, String securityName, int securityLevel) {
        // Keyed by name plus level so that changing a device from authNoPriv to
        // authPriv re-registers rather than silently reusing the old keys.
        String cacheKey = securityName + '/' + securityLevel;
        if (!registeredUsers.add(cacheKey)) {
            return;
        }

        OID authProtocol = null;
        OctetString authPassphrase = null;
        OID privProtocol = null;
        OctetString privPassphrase = null;

        if (securityLevel >= SecurityLevel.AUTH_NOPRIV) {
            authProtocol = authProtocolOid(request.param("snmpAuthProtocol", "SHA"));
            authPassphrase = new OctetString(request.param("snmpAuthPassphrase", ""));
        }
        if (securityLevel == SecurityLevel.AUTH_PRIV) {
            privProtocol = privProtocolOid(request.param("snmpPrivProtocol", "AES128"));
            privPassphrase = new OctetString(request.param("snmpPrivPassphrase", ""));
        }

        snmp.getUSM().addUser(new OctetString(securityName),
                new UsmUser(new OctetString(securityName),
                        authProtocol, authPassphrase, privProtocol, privPassphrase));
    }

    private static OID authProtocolOid(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "MD5" -> AuthMD5.ID;
            case "SHA256", "SHA-256", "HMAC192SHA256" -> AuthHMAC192SHA256.ID;
            case "SHA512", "SHA-512", "HMAC384SHA512" -> AuthHMAC384SHA512.ID;
            default -> AuthSHA.ID;
        };
    }

    private static OID privProtocolOid(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "DES" -> PrivDES.ID;
            case "AES192" -> PrivAES192.ID;
            case "AES256" -> PrivAES256.ID;
            default -> PrivAES128.ID;
        };
    }

    @PreDestroy
    public void close() {
        try {
            snmp.close();
        } catch (IOException e) {
            log.warn("Error closing the SNMP transport: {}", e.getMessage());
        }
    }
}
