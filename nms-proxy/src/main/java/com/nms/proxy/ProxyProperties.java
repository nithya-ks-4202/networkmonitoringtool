package com.nms.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How this proxy reaches the server and how hard it works.
 */
@Component
@ConfigurationProperties(prefix = "nms.proxy")
public class ProxyProperties {

    /** Base URL of the monitoring server, e.g. {@code https://monitoring.example.com}. */
    private String serverUrl = "http://localhost:8080";

    /** Enrolment token issued when the proxy was created. */
    private String token = "";

    /** Name used in logs; the server's own name for this proxy wins once enrolled. */
    private String name = "proxy";

    /** How often to poll the server for a configuration change. */
    private Duration configInterval = Duration.ofMinutes(1);

    /** How often to upload buffered results. */
    private Duration uploadInterval = Duration.ofSeconds(10);

    /** Results per upload. Large enough to be efficient, small enough to retry cheaply. */
    private int uploadBatchSize = 1000;

    /** Worker threads executing checks. Checks are I/O-bound, so this exceeds the core count. */
    private int pollerThreads = 25;

    /**
     * Where results are buffered when the server is unreachable.
     *
     * <p>On disk rather than in memory: a site whose link drops overnight would
     * otherwise lose every sample collected in the meantime, which is exactly
     * the period someone will later want to look at.
     */
    private String spoolDirectory = "./spool";

    /**
     * Ceiling on the buffer.
     *
     * <p>Bounded because a proxy that fills its disk stops collecting *and*
     * takes the host with it. Oldest results are dropped first: recent data is
     * what an operator responding now actually needs.
     */
    private long spoolMaxBytes = 512L * 1024 * 1024;

    /** How long to wait for the server before treating a request as failed. */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * Touched whenever the server is successfully reached.
     *
     * <p>Stands in for a health endpoint. The proxy listens on nothing, so the
     * container runtime checks the age of this file instead -- which also
     * distinguishes "running" from "running and actually talking to the
     * server", the failure that matters.
     */
    private String heartbeatFile = "./heartbeat";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        // Trailing slashes would produce doubled separators when paths are
        // appended, which some reverse proxies reject outright.
        this.serverUrl = serverUrl.endsWith("/")
                ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Duration getConfigInterval() {
        return configInterval;
    }

    public void setConfigInterval(Duration configInterval) {
        this.configInterval = configInterval;
    }

    public Duration getUploadInterval() {
        return uploadInterval;
    }

    public void setUploadInterval(Duration uploadInterval) {
        this.uploadInterval = uploadInterval;
    }

    public int getUploadBatchSize() {
        return uploadBatchSize;
    }

    public void setUploadBatchSize(int uploadBatchSize) {
        this.uploadBatchSize = uploadBatchSize;
    }

    public int getPollerThreads() {
        return pollerThreads;
    }

    public void setPollerThreads(int pollerThreads) {
        this.pollerThreads = pollerThreads;
    }

    public String getSpoolDirectory() {
        return spoolDirectory;
    }

    public void setSpoolDirectory(String spoolDirectory) {
        this.spoolDirectory = spoolDirectory;
    }

    public long getSpoolMaxBytes() {
        return spoolMaxBytes;
    }

    public void setSpoolMaxBytes(long spoolMaxBytes) {
        this.spoolMaxBytes = spoolMaxBytes;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getHeartbeatFile() {
        return heartbeatFile;
    }

    public void setHeartbeatFile(String heartbeatFile) {
        this.heartbeatFile = heartbeatFile;
    }
}
