# ---------------------------------------------------------------------------
# Monitoring server.
#
# Built in two stages so the runtime image carries no Maven, no sources and no
# build cache -- a smaller image to pull on every deploy, and a smaller surface
# to audit.
# ---------------------------------------------------------------------------

# Overridable so the images can be built against a registry mirror. Sites that
# cannot reach Docker Hub -- an air-gapped network, or a company that pulls
# everything through Artifactory -- retag these two upstream and point the build
# at them, rather than having to patch this file on every upgrade.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# Dependencies resolve in their own layer, keyed on the POMs alone. Editing a
# source file then rebuilds in seconds rather than re-downloading the world.
COPY pom.xml .
COPY nms-common/pom.xml    nms-common/
COPY nms-collector/pom.xml nms-collector/
COPY nms-server/pom.xml    nms-server/
COPY nms-proxy/pom.xml     nms-proxy/
COPY nms-agent/pom.xml     nms-agent/
RUN mvn -B -q dependency:go-offline -pl nms-common,nms-collector,nms-server -am || true

COPY nms-common    nms-common/
COPY nms-collector nms-collector/
COPY nms-server    nms-server/
RUN mvn -B -q -pl nms-server -am package -DskipTests

# ---------------------------------------------------------------------------

FROM ${RUNTIME_IMAGE}

# iputils-ping is not optional. Raw ICMP sockets need privileges the JVM cannot
# request, so the collector shells out to ping; without it every ICMP check
# degrades to a TCP probe that cannot measure loss or latency, and the camera
# wall silently becomes far less useful than it looks.
RUN apt-get update \
 && apt-get install -y --no-install-recommends iputils-ping curl \
 && rm -rf /var/lib/apt/lists/*

# Runs unprivileged. A monitoring server holds credentials for every device on
# the network, which makes it a worthwhile target and a bad thing to run as root.
RUN groupadd --system --gid 1001 nms \
 && useradd --system --uid 1001 --gid nms --home /app --shell /usr/sbin/nologin nms

WORKDIR /app
COPY --from=build --chown=nms:nms /build/nms-server/target/nms-server-*.jar app.jar

USER nms
EXPOSE 8080

# Container-aware heap sizing: a fixed -Xmx is wrong the moment the memory limit
# changes, and the JVM's default without this is a fraction of the host's RAM
# rather than of the container's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Uses the liveness probe rather than the aggregate: readiness depends on the
# database, and a server that is up but waiting for a slow managed database to
# accept connections should not be reported as a dead container.
HEALTHCHECK --interval=20s --timeout=4s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
