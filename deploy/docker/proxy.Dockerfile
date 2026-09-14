# ---------------------------------------------------------------------------
# On-premise proxy collector.
#
# Deployed inside a customer network, so it is kept as small and as boring as
# possible: it makes only outbound connections, listens on nothing, and runs
# unprivileged.
# ---------------------------------------------------------------------------

# Overridable for registry mirrors -- see server.Dockerfile. It matters more
# here than anywhere else: the proxy is the component that gets installed on
# someone else's network, which is exactly where Docker Hub tends to be blocked.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

COPY pom.xml .
COPY nms-common/pom.xml    nms-common/
COPY nms-collector/pom.xml nms-collector/
COPY nms-server/pom.xml    nms-server/
COPY nms-proxy/pom.xml     nms-proxy/
COPY nms-agent/pom.xml     nms-agent/
RUN mvn -B -q dependency:go-offline -pl nms-common,nms-collector,nms-proxy -am || true

COPY nms-common    nms-common/
COPY nms-collector nms-collector/
COPY nms-proxy     nms-proxy/
RUN mvn -B -q -pl nms-proxy -am package -DskipTests

# ---------------------------------------------------------------------------

FROM ${RUNTIME_IMAGE}

# The proxy is the component that actually pings the cameras, so ping matters
# here even more than on the server. No curl: the health check below reads a
# file rather than making a request, so nothing needs an HTTP client.
RUN apt-get update \
 && apt-get install -y --no-install-recommends iputils-ping \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 nms \
 && useradd --system --uid 1001 --gid nms --home /app --shell /usr/sbin/nologin nms

WORKDIR /app
COPY --from=build --chown=nms:nms /build/nms-proxy/target/nms-proxy-*.jar app.jar

# Results are buffered here when the link to the server is down. Mount a volume
# over it: an overnight outage should cost latency, not data.
RUN mkdir -p /var/lib/nms/spool && chown -R nms:nms /var/lib/nms
VOLUME ["/var/lib/nms/spool"]

USER nms

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    NMS_PROXY_SPOOL_DIR=/var/lib/nms/spool \
    NMS_PROXY_HEARTBEAT_FILE=/var/lib/nms/heartbeat

# The proxy listens on nothing, so there is no endpoint to poll. It touches a
# file whenever it reaches the server, and this checks that file's age.
#
# That is the more useful question anyway. An HTTP 200 from the proxy's own
# health endpoint would prove only that its JVM is running -- a proxy that is up
# but has not reached the server in an hour would report itself perfectly
# healthy, and that is precisely the failure worth catching.
#
# Five minutes is several configuration polls (one a minute by default), so a
# single missed request does not flap the container.
HEALTHCHECK --interval=60s --timeout=5s --start-period=90s --retries=2 \
  CMD test -f "$NMS_PROXY_HEARTBEAT_FILE" \
   && [ $(( $(date +%s) - $(stat -c %Y "$NMS_PROXY_HEARTBEAT_FILE") )) -lt 300 ]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
