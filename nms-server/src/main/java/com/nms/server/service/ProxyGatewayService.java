package com.nms.server.service;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.protocol.ProxyConfigResponse;
import com.nms.common.protocol.ProxyDataRequest;
import com.nms.common.protocol.ProxyDataResponse;
import com.nms.server.domain.Item;
import com.nms.server.domain.Proxy;
import com.nms.server.poller.CheckRequestFactory;
import com.nms.server.poller.ValueProcessor;
import com.nms.server.repository.CoreRepositories.ProxyRepository;
import com.nms.server.repository.ItemRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Serves on-premise proxies: authentication, configuration and data intake.
 */
@Service
public class ProxyGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ProxyGatewayService.class);

    /** Characters of the token kept in clear so a candidate can be found. */
    private static final int TOKEN_PREFIX_LENGTH = 8;

    private final ProxyRepository proxies;
    private final ItemRepository items;
    private final CheckRequestFactory requestFactory;
    private final ValueProcessor valueProcessor;
    private final PasswordEncoder passwordEncoder;
    private final int uploadIntervalSeconds;

    /**
     * Batch identifiers already accepted.
     *
     * <p>A proxy re-sends a batch it never saw acknowledged, which is the
     * normal outcome of a dropped connection. The history tables guard against
     * duplicate rows on their own, but remembering the batch lets the proxy be
     * told the truth -- that its values were already stored -- rather than
     * having them silently absorbed.
     */
    private final Cache<String, Boolean> recentBatches = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public ProxyGatewayService(ProxyRepository proxies,
                               ItemRepository items,
                               CheckRequestFactory requestFactory,
                               ValueProcessor valueProcessor,
                               PasswordEncoder passwordEncoder,
                               @Value("${nms.proxy.upload-interval-seconds:10}") int uploadIntervalSeconds) {
        this.proxies = proxies;
        this.items = items;
        this.requestFactory = requestFactory;
        this.valueProcessor = valueProcessor;
        this.passwordEncoder = passwordEncoder;
        this.uploadIntervalSeconds = uploadIntervalSeconds;
    }

    /**
     * Verifies an enrolment token.
     *
     * <p>The clear-text prefix narrows the search to a single candidate before
     * any hash is checked. Verifying every proxy's hash in turn would be slow
     * and, worse, would leak through timing how many proxies exist.
     */
    @Transactional(readOnly = true)
    public Optional<Proxy> authenticate(String token) {
        if (token == null || token.length() < TOKEN_PREFIX_LENGTH) {
            return Optional.empty();
        }

        String prefix = token.substring(0, TOKEN_PREFIX_LENGTH);
        return proxies.findByTokenPrefix(prefix).stream()
                .filter(Proxy::isEnabled)
                .filter(proxy -> proxy.getTokenHash() != null
                        && passwordEncoder.matches(token, proxy.getTokenHash()))
                .findFirst();
    }

    /**
     * Builds the item assignment for a proxy.
     *
     * @param knownRevision the revision the proxy already holds; when it
     *                      matches, an empty unchanged response is returned
     *                      instead of the full set
     */
    @Transactional(readOnly = true)
    public ProxyConfigResponse buildConfiguration(Proxy proxy, long knownRevision) {
        if (knownRevision == proxy.getConfigRevision()) {
            return ProxyConfigResponse.unchanged(proxy.getId(), proxy.getConfigRevision(),
                    uploadIntervalSeconds);
        }

        List<Item> assigned = items.findForProxy(proxy.getId());
        List<CheckRequest> requests = new ArrayList<>(assigned.size());

        for (Item item : assigned) {
            try {
                requests.add(requestFactory.build(item));
            } catch (CheckRequestFactory.UncollectableItemException e) {
                // Recorded against the item and left out of the assignment: a
                // proxy cannot fix a misconfigured host, and sending it work it
                // must fail would fill its log with noise every cycle.
                valueProcessor.recordFailure(item.getId(), e.getMessage());
            }
        }

        log.info("Proxy '{}' fetched revision {} with {} item(s)",
                proxy.getName(), proxy.getConfigRevision(), requests.size());

        return new ProxyConfigResponse(proxy.getId(), proxy.getConfigRevision(), true,
                requests, Instant.now(), uploadIntervalSeconds);
    }

    /** Stores a batch of values uploaded by a proxy. */
    @Transactional
    public ProxyDataResponse acceptData(Proxy proxy, ProxyDataRequest request) {
        long clockSkewMs = request.proxyClock() == null ? 0
                : Duration.between(request.proxyClock(), Instant.now()).toMillis();

        proxies.recordContact(proxy.getId(), Instant.now(), request.version(),
                request.queueDepth(), clockSkewMs);

        if (request.batchId() != null && recentBatches.getIfPresent(request.batchId()) != null) {
            log.debug("Proxy '{}' re-sent batch {}; already stored", proxy.getName(), request.batchId());
            return new ProxyDataResponse(request.batchId(), 0,
                    request.results() == null ? 0 : request.results().size(),
                    proxy.getConfigRevision(), clockSkewMs);
        }

        // A clock far out of step makes every stored timestamp wrong, and the
        // resulting graphs and trigger windows are subtly and confusingly
        // broken. Worth saying loudly rather than absorbing silently.
        if (Math.abs(clockSkewMs) > Duration.ofMinutes(1).toMillis()) {
            log.warn("Proxy '{}' clock differs from the server by {}s; "
                            + "collected timestamps will be off by the same amount",
                    proxy.getName(), clockSkewMs / 1000);
        }

        Set<Long> permitted = permittedItemIds(proxy);
        int accepted = 0;
        int rejected = 0;

        for (CheckResult result : request.results() == null ? List.<CheckResult>of() : request.results()) {
            // A proxy may only write values for items actually assigned to it.
            // Without this check, one customer's proxy could write into another
            // customer's items by guessing an identifier.
            if (!permitted.contains(result.itemId())) {
                rejected++;
                continue;
            }
            valueProcessor.accept(result);
            accepted++;
        }

        if (request.batchId() != null) {
            recentBatches.put(request.batchId(), Boolean.TRUE);
        }

        if (rejected > 0) {
            log.warn("Proxy '{}' sent {} value(s) for items it is not assigned; they were discarded",
                    proxy.getName(), rejected);
        }

        return new ProxyDataResponse(request.batchId(), accepted, rejected,
                proxy.getConfigRevision(), clockSkewMs);
    }

    private Set<Long> permittedItemIds(Proxy proxy) {
        Set<Long> permitted = new HashSet<>();
        items.findForProxy(proxy.getId()).forEach(item -> permitted.add(item.getId()));
        return permitted;
    }

    @Transactional
    public void recordEnrolment(Proxy proxy, String version) {
        proxies.recordContact(proxy.getId(), Instant.now(), version, 0, 0);
        log.info("Proxy '{}' enrolled (version {})", proxy.getName(), version);
    }

    /**
     * Creates a proxy and returns its token.
     *
     * <p>The token is returned once and only its hash is stored, so a database
     * leak does not hand out working collector credentials. An operator who
     * loses it issues a new one.
     */
    @Transactional
    public NewProxy create(Long tenantId, String name, String description) {
        proxies.findByTenantIdAndName(tenantId, name).ifPresent(existing -> {
            throw new IllegalArgumentException("A proxy named '" + name + "' already exists");
        });

        String token = generateToken();

        Proxy proxy = new Proxy();
        proxy.setTenantId(tenantId);
        proxy.setName(name);
        proxy.setDescription(description == null ? "" : description);
        proxy.setTokenHash(passwordEncoder.encode(token));
        proxy.setTokenPrefix(token.substring(0, TOKEN_PREFIX_LENGTH));
        proxies.save(proxy);

        log.info("Proxy '{}' created", name);
        return new NewProxy(proxy.getId(), name, token);
    }

    /** Replaces a proxy's token, invalidating the previous one immediately. */
    @Transactional
    public NewProxy regenerateToken(Long tenantId, Long proxyId) {
        Proxy proxy = proxies.findById(proxyId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such proxy: " + proxyId));

        String token = generateToken();
        proxy.setTokenHash(passwordEncoder.encode(token));
        proxy.setTokenPrefix(token.substring(0, TOKEN_PREFIX_LENGTH));
        proxies.save(proxy);

        log.info("Token regenerated for proxy '{}'", proxy.getName());
        return new NewProxy(proxy.getId(), proxy.getName(), token);
    }

    private static String generateToken() {
        byte[] material = new byte[36];
        new SecureRandom().nextBytes(material);
        // URL-safe so it can be pasted into an environment variable or a
        // command line without quoting, which is what it is used for.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    public int uploadIntervalSeconds() {
        return uploadIntervalSeconds;
    }

    /** A newly created proxy, including the one-time token. */
    public record NewProxy(Long proxyId, String name, String token) {
    }
}
