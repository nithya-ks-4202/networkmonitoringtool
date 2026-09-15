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
| Web interface: overview, problems, hosts, camera wall, graphs, discovery | Working |
| Adding hosts through the interface, with template and camera settings | Working |
| Network discovery: sweep a range, classify what answers, add it | Working |
| Multi-tenancy in the data model | Working |
| Low-level discovery (creating items from a discovery rule) | **Not implemented** |
| Network maps, SNMP traps, IPMI, JMX collection | **Not implemented** |

The schema and domain model cover the unimplemented areas, and the interfaces
they need are in place; the execution is not written. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for what that would involve.

This is a working foundation with the core monitoring loop complete end to end,
not a finished replacement for a product with twenty-five years behind it.

## Running it

**Running it on your own hardware?** [docs/ON-PREMISE.md](docs/ON-PREMISE.md) is the
runbook: sizing, TLS, backups, upgrades, remote sites and troubleshooting.

**On Windows?** [docs/WINDOWS.md](docs/WINDOWS.md) covers the server under WSL2,
and building the agent and proxy as `.exe`/`.msi` installers that run as Windows
services.

### Locally, with Docker Compose

Needs a Docker runtime. On Linux that is Docker Engine
(`curl -fsSL https://get.docker.com | sudo sh`); on a **Mac** it is Colima or
Docker Desktop, because macOS runs Linux containers in a VM and has no engine of
its own — see [docs/ON-PREMISE.md](docs/ON-PREMISE.md#running-it-on-the-mac).

```bash
./deploy/init-env.sh            # writes .env with generated secrets
docker compose up -d

# The generated admin password. Printed once, at the first start against an
# empty database -- so read it from the log rather than waiting for it to
# scroll past.
docker compose logs server | grep -A 4 "administrator account"
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

## Finding what is on the network

**Discovery → New scan.** Give it a range — `10.30.5.1-254`, `10.30.5.0/24`, a
single address, or several separated by commas — and it sweeps them, reporting
what answered and what each device looks like.

It does not create hosts by itself. It shows you the evidence ("RTSP (554) is
open", "answered ONVIF", "SNMP description looks like a camera") and you press
**Monitor** on the ones you want, correcting the suggestion first if it is
wrong. Automatic creation sounds convenient right up to the scan that silently
adds three hundred laptops and a printer to the estate.

Turn on the **ONVIF** probe when you are looking for cameras: it is the one
signal that is conclusive rather than suggestive, because nothing else
implements it. **SNMP** is worth enabling on a mixed network — it names the
device, which is usually the name the network team already calls it by.

Two things worth knowing before pointing it at a production VLAN:

- Sweeps are deliberately slow, 32 addresses at a time by default. This is the
  only part of the platform that sends traffic to machines it has never been
  told about, and a fast sweep of an unfamiliar network is indistinguishable
  from a port scan.
- Ranges over 65,536 addresses are refused. A `/8` at a realistic rate takes
  days, so accepting it would mean a scan that never finishes.

If the range is not routable from the server — which is normal for an isolated
camera VLAN — no configuration fixes that. Run a proxy inside the network
instead.

## Cameras

Create a host with class `Camera` and link **Template: IP camera**. It brings
thirteen items and eight triggers.

The template is built around two failures that every naive check passes.

**The stream is dead but the camera is not.** A hung encoder, an exhausted
session limit or a wiped configuration still answers ICMP and still accepts TCP
on port 554 — while recording nothing. Only an RTSP negotiation proves the
stream endpoint is alive, so the template runs one, with digest authentication,
and optionally a `DESCRIBE` to catch a channel path that a firmware update
renumbered.

**The SD card is dead and everything else is perfect.** This one is worse,
because the camera passes every check above: it pings, it negotiates RTSP, it
serves live video, ONVIF reports it healthy. Live view is flawless and there is
no footage. It surfaces weeks later when someone asks for an incident recording.

So the template reads the card itself — present, writable, not in an error
state, and not full. Three cases are separated because they need different
responses:

| Trigger | Meaning |
|---|---|
| **Camera is not recording (storage failed)** | A card is fitted but failed, unformatted or read-only |
| **Camera has no storage card** | No card detected at all — the end state of a worn or counterfeit SD |
| **Camera storage is nearly full** | Below the free-space floor; harmless if overwrite is on |

A read-only card is worth calling out: the status often still reads `ok` and the
camera carries on as though recording. It is the late stage of flash wear, and
it needs replacing rather than reformatting.

No interoperable standard reports any of this — ONVIF describes configured
storage rather than card health — so it reads the vendor's API. **Hikvision
ISAPI** is what is implemented. On other makes the storage items report as
unsupported with the reason, and the rest of the template is unaffected.

Its triggers depend on the offline trigger, so a camera that loses power raises
one problem rather than seven.

Per-camera overrides go in macros:

| Macro | Purpose |
|---|---|
| `{$CAMERA.RTSP.PORT}` | RTSP port, if not 554 |
| `{$CAMERA.RTSP.PATH}` | Stream path; varies by manufacturer |
| `{$CAMERA.USER}` / `{$CAMERA.PASSWORD}` | RTSP, ONVIF and storage-API credentials |
| `{$CAMERA.DOWN.TIME}` | How long unreachable before it counts as offline |
| `{$CAMERA.STORAGE.PATH}` | Vendor storage API path; defaults to Hikvision ISAPI |
| `{$CAMERA.STORAGE.PFREE.MIN}` | Free-space percentage below which the card counts as full |

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

**Alerting, verified by receiving one:** a real problem opening, an
escalation climbing its ladder with a notification delivered at each rung, a
host edited through the API, the problem recovering, and a recovery
notification delivered — all observed arriving at a receiver outside the
system, with every alert row reaching `SENT`.

**Discovery and host creation, verified in a browser:** creating a scan,
sweeping a range, four devices found and classified, promoting one with the
suggested name and template, the device dropping off the pending list
afterwards, and adding a second host by hand through the form — including the
camera settings and the per-vendor stream-path shortcuts.

**Verified by running it:** the schema against PostgreSQL 16 including the
TimescaleDB fallback path; server startup, migrations and the REST API; host
creation with template linking, expression rewriting and dependency copying;
severity filtering and ordering; the agent answering its wire protocol with real
CPU, memory, filesystem, load and process values; the web interface rendered in a
browser across every page.

**The full proxy path, end to end:** creating a proxy through the API, token
issue and rejection, enrolment, configuration fetch, local polling, upload, and
the resulting values driving trigger evaluation to open the right problems —
including a trigger dependency correctly *not* suppressing faults whose master
trigger was healthy.

**Backup and restore:** `deploy/backup.sh` run against a live database, and the
dump restored into a fresh one with data intact.

**Verified by test:** 73 tests over the collector pollers, the failure
describer, and the trigger expression engine — parser, evaluator, and the
propagation of "no data" through both.

**The server and proxy images, built and run:** both Dockerfiles built, both
containers started against PostgreSQL 16 and reported healthy. The server
migrated a fresh database, answered the API, and polled a host linked to the
ICMP template on schedule — `icmpping=1`, `icmppingloss=0`, `icmppingsec=0.0002`
written to history. The proxy refused to start without a token, enrolled with
one, and its heartbeat health check reported healthy.

Building them is what found the two defects fixed in `3ab35c0` — in particular
that the server's poller claimed nothing at all, which no test caught and
reading the code did not reveal.

**Not verified:** the web image's runtime stage. Its build stage runs (`npm ci`
and `npm run build` produce `web/dist`), but nginx's template rendering and the
`/api` proxy pass have not executed in a container. The Helm chart was not
rendered by `helm template` either; its templates are structurally checked only.
SNMP and ONVIF have unit coverage and no integration test against real hardware.
There is no API for listing templates — they are linked by name when a host is
created, and the interface offers no way to browse them.

## Licence

Proprietary — internal use.
