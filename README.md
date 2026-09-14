# Network Monitoring Platform

Monitors cameras, network devices and servers, raises problems when something
breaks, and escalates them to whoever is on call. Architecturally it follows
Zabbix — hosts, items, triggers, events, actions, templates, proxies and agents,
with compatible trigger expression syntax — so existing operational knowledge
carries over. It is built to be hosted centrally and to reach private networks
through on-premise collectors.

## What it does today

| Area | State |
|---|---|
| ICMP, TCP, SNMP v1/v2c/v3, HTTP(S), RTSP, ONVIF collection | Working |
| Agent (passive + active) for Linux, Windows, macOS metrics | Working |
| Templates with items, triggers and dependencies | Working |
| Trigger expression engine, problem lifecycle, dependencies | Working |
| Actions, escalation ladders, email / Slack / Teams / PagerDuty / webhook | Working |
| Maintenance windows and problem suppression | Working |
| On-premise proxy with store-and-forward buffering | Working |
| Web interface: overview, problems, hosts, camera wall, graphs | Working |
| Multi-tenancy in the data model | Working |
| Low-level discovery (creating items from a discovery rule) | **Not implemented** |
| Network discovery (scanning a subnet for new devices) | **Not implemented** |
| Network maps, SNMP traps, IPMI, JMX collection | **Not implemented** |

The schema and domain model cover the unimplemented areas, and the interfaces
they need are in place; the execution is not written. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for what that would involve.

This is a working foundation with the core monitoring loop complete end to end,
not a finished replacement for a product with twenty-five years behind it.

## Running it

### Locally, with Docker Compose

```bash
cp .env.example .env
# Set NMS_DB_PASSWORD and NMS_JWT_SECRET. Nothing has a working default.
#   openssl rand -base64 24   # database password
#   openssl rand -base64 48   # JWT secret

docker compose up -d
docker compose logs -f server   # the generated admin password is printed once
```

The interface is then on <http://localhost:3000>.

To populate it with a small estate so there is something to look at:

```bash
PGHOST=localhost PGUSER=nms PGDATABASE=nms \
  ./deploy/demo-data.sh http://localhost:8080 admin "$NMS_ADMIN_PASSWORD"
```

### Hosted, on Kubernetes

```bash
kubectl create secret generic nms-db --from-literal=password='...'
kubectl create secret generic nms-security \
  --from-literal=jwt-secret="$(openssl rand -base64 48)"

helm install nms deploy/helm \
  --set database.host=your-db.example.com \
  --set database.existingSecret=nms-db \
  --set security.existingSecret=nms-security \
  --set ingress.host=monitoring.example.com \
  --set baseUrl=https://monitoring.example.com
```

The server is stateless — scheduling state lives in the database and work is
claimed with `SELECT ... FOR UPDATE SKIP LOCKED` — so replicas need no leader
election, no sticky sessions and no coordination. Scale it like any web service.

Use a managed Postgres, ideally with TimescaleDB. The migrations detect the
extension and turn the history tables into compressed hypertables; without it
they run as plain tables and retention becomes a delete rather than a chunk drop.

## Monitoring a private network from a hosted server

This is the part that makes central hosting practical. Cameras and switches on a
customer LAN have no route from the internet, and no security team will open
inbound firewall rules to a monitoring vendor.

A **proxy** runs inside that network. It polls locally and pushes results out
over an ordinary outbound HTTPS connection — the same direction as any other
outbound traffic, through the same egress controls. Nothing listens on it.

```
   Hosted                              Customer network
 ┌──────────┐                       ┌─────────────────────────┐
 │  server  │ ◄── outbound HTTPS ───┤  proxy ──► cameras      │
 │    +     │     (proxy dials out) │        ──► switches     │
 │  web UI  │                       │        ──► agents       │
 └────┬─────┘                       └─────────────────────────┘
      │
 ┌────▼──────────────┐
 │ Postgres /        │
 │ TimescaleDB       │
 └───────────────────┘
```

Create the proxy in the interface, take the token it shows once, then on a
machine inside the network:

```bash
docker run -d --name nms-proxy \
  --cap-add NET_RAW \
  -e NMS_SERVER_URL=https://monitoring.example.com \
  -e NMS_PROXY_TOKEN='<the token>' \
  -e NMS_PROXY_NAME=branch-office \
  -v nms-spool:/var/lib/nms/spool \
  ghcr.io/your-org/nms-proxy:1.0.0
```

Assign hosts to that proxy and it collects them. If the link drops it buffers to
disk and uploads the backlog when the link returns, so an overnight outage costs
latency rather than data.

## Monitoring a server

Install the agent on the machine:

```bash
java -jar nms-agent.jar /etc/nms/agent.conf
```

By default it binds to loopback only. An agent answering the whole network lets
anyone who can reach the port enumerate that host's filesystems, processes and
logged-in users, so the servers permitted to ask must be listed explicitly:

```properties
agent.hostname=web-01
agent.allowed.servers=10.20.0.5,10.20.0.6
```

Where the server cannot reach the host — behind NAT, on a laptop — invert the
direction and let the agent push instead:

```properties
agent.active.enabled=true
agent.server.url=https://monitoring.example.com
```

Then create a host named `web-01` and link **Template: Linux by agent**.

## Cameras

Create a host with class `Camera` and link **Template: IP camera**. It brings
seven items and five triggers.

The distinction that matters: a camera with a hung encoder, an exhausted stream
session limit, or a wiped configuration still answers ICMP and still accepts TCP
on port 554 — while recording nothing. Only an RTSP negotiation proves the stream
endpoint is alive, so the template runs one, with digest authentication, and
optionally a `DESCRIBE` to catch a channel path that a firmware update renumbered.

Its triggers depend on the offline trigger, so a camera that loses power raises
one problem rather than four.

Per-camera overrides go in macros:

| Macro | Purpose |
|---|---|
| `{$CAMERA.RTSP.PORT}` | RTSP port, if not 554 |
| `{$CAMERA.RTSP.PATH}` | Stream path; varies by manufacturer |
| `{$CAMERA.USER}` / `{$CAMERA.PASSWORD}` | RTSP and ONVIF credentials |
| `{$CAMERA.DOWN.TIME}` | How long unreachable before it counts as offline |

## Layout

```
nms-common/      Protocol types shared by server, proxy and agent
nms-collector/   Protocol pollers, shared by server and proxy
nms-server/      Configuration, scheduling, triggers, alerting, API
nms-proxy/       On-premise collector
nms-agent/       Host agent
web/             React interface
deploy/          Dockerfiles, Compose, Helm chart, demo data
docs/            Architecture and operations
```

## Building

```bash
mvn verify          # all modules and tests
cd web && npm ci && npm run build
```

Requires JDK 21 and Node 22.

## Verification status

Honest account of what has and has not been exercised.

**Verified by running it:** the schema against PostgreSQL 16 including the
TimescaleDB fallback path; server startup, migrations and the REST API; host
creation with template linking, expression rewriting and dependency copying;
severity filtering and ordering; the agent answering its wire protocol with real
CPU, memory, filesystem, load and process values; the web interface rendered in a
browser across every page.

**Verified by test:** 65 tests over the collector pollers and the trigger
expression engine — parser, evaluator, and the propagation of "no data" through
both.

**Not verified:** the Dockerfiles and Compose file were not built (no Docker
daemon in the build environment) and the Helm chart was not rendered by `helm
template` (helm could not be downloaded). Their YAML parses and the templates are
structurally sound, but they have not been run. SNMP, ONVIF and the proxy upload
path have unit coverage and no integration test against real hardware.

## Licence

Proprietary — internal use.
