# ---------------------------------------------------------------------------
# On-premise proxy collector.
#
# Deployed inside a customer network, so it is kept as small and as boring as
# possible: it makes only outbound connections, listens on nothing, and runs
# unprivileged.
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
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

FROM eclipse-temurin:21-jre-jammy

# The proxy is the component that actually pings the cameras, so ping matters
# here even more than on the server.
RUN apt-get update \
 && apt-get install -y --no-install-recommends iputils-ping curl \
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
    NMS_PROXY_SPOOL_DIR=/var/lib/nms/spool

HEALTHCHECK --interval=30s --timeout=4s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
