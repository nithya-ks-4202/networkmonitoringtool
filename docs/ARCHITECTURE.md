# Architecture

Why the pieces are shaped the way they are, and what the consequences were.

## The collection loop

```
  item due ──► claim ──► poll ──► preprocess ──► store ──► evaluate triggers
                 │                                              │
          SKIP LOCKED                                    problem opened
                                                                │
                                                      action matched ──► escalate ──► deliver
```

Every stage is deliberately separable. The poller does not know what a trigger
is; the trigger engine does not know what SNMP is; the alerter does not know
where a value came from.

### Claiming work

Due items are claimed with `SELECT ... FOR UPDATE SKIP LOCKED` rather than
partitioned across instances in advance.

That one clause is what makes the server horizontally scalable. Each instance
takes rows the others have not locked, with no leader election, no shard
assignment and no coordination protocol. An instance that dies mid-batch simply
releases its locks and the next pass picks the work up. The same mechanism
drains the alert queue, so no page is ever delivered twice.

The alternative — assigning item ranges to instances — requires knowing how many
instances there are, which is exactly what an autoscaler makes untrue.

### Scheduling

An item's next check is derived from its identifier, not from "now plus the
interval". Items therefore spread evenly across each interval instead of
bunching: without it, everything created in one bulk import, or everything on an
instance that just restarted, fires in the same second and leaves the rest of the
minute idle.

It also keeps samples evenly spaced rather than drifting by the poll duration
each cycle, which matters when a rate is computed from the gap between them.

### Storage

History is written in batches through a bounded buffer.

Monitoring produces an unrelenting stream of tiny writes — a thousand cameras on
a one-minute interval is over fifty inserts a second, each a handful of bytes.
Written individually, nearly all the time goes on round trips. Batched, it is a
few statements a second.

The buffer is bounded and drops values when full rather than growing. An
unbounded queue converts a slow database into an out-of-memory crash that also
loses everything already buffered — turning a recoverable problem into an
unrecoverable one.

Raw history and hourly trends are separate tables. A year-long graph reads
trends; without them it would read a year of raw samples to draw a thousand
pixels. When TimescaleDB is present both become compressed hypertables, so
retention is a chunk drop rather than a delete of a hundred million rows.

## The decision that shapes the trigger engine

**"No data" is not "false".**

When an expression references an item with nothing to read, it evaluates to
`Unknown`, which propagates through arithmetic and comparison and puts the
trigger in the `UNKNOWN` state — not `OK`.

The alternative is worse than it sounds. `max(/camera/icmpping,3m)=0` means "the
camera has not answered". If an item with no data returned 0, that expression
would be *true* for a camera that was never polled at all — and a trigger
watching a host that stopped reporting entirely would read as healthy. That is
the monitoring equivalent of an all-clear because the sensor was unplugged.

`Unknown` short-circuits where the answer is genuinely decided: `false and
unknown` is false, because no value of the unknown operand could make it true.

`nodata()` is the one function that returns a definite answer with no data,
because silence is precisely what it measures. It is how "the agent stopped
responding" is distinguished from "the agent says everything is fine".

### Suppressing the storm

Two mechanisms, for two different problems.

**Trigger dependencies** stop one fault reporting as many. The camera template's
stream, web-interface and ONVIF triggers depend on its offline trigger, so a
camera that loses power raises one problem instead of four. The same structure
scales up: a switch's ICMP trigger suppresses every camera behind it.

**Recovery expressions** stop one fault reporting repeatedly. A trigger can
require a separate expression to become true before it recovers — alert above
90%, recover below 80% — so a metric sitting on its threshold does not produce a
problem and a recovery every polling interval.

## Alerting

An escalation is a state machine per `(action, problem)` pair, with a unique
constraint on that pair. The constraint is what makes concurrent event
processing safe: two instances handling the same event race to insert, one wins,
and the loser's duplicate is rejected by the database rather than producing a
second set of pages.

Escalation **pauses** on acknowledgement and during maintenance rather than
cancelling. A fault that begins inside a maintenance window is still a fault and
must reach someone when the window closes. The same reasoning applies to
suppressed problems: they are recorded and visible, they simply do not page.

Alerts are persisted before delivery is attempted. A queued row that is never
marked sent survives a crash and is retried; a message sent from memory is lost,
and nobody finds out until the post-incident review. It also answers "was I
actually paged?", which is the first question asked after a missed incident.

Delivery failures are classified. A rejected address is permanent and retrying
wastes attempts a transient SMTP outage needs; a 5xx or a 429 is the provider's
problem and usually passes.

## Reaching private networks

The server cannot reach a customer LAN, and no security team will open inbound
firewall rules to a monitoring vendor. A proxy inside the network polls locally
and dials out.

Consequences that follow from the proxy having no database:

- A check request must be **self-contained**. Address, port, credentials,
  interval and expanded macros all travel with it, because the proxy cannot look
  anything up.
- Configuration is **revision-numbered**. The proxy sends the revision it holds
  and gets `changed=false` when nothing has moved, so a proxy responsible for
  thousands of items does not re-download its assignment every minute over
  somebody's office broadband.
- Results are **buffered to disk**, bounded, oldest-dropped-first. An overnight
  link failure should cost latency, not data; a proxy that fills its disk stops
  collecting and takes the host with it.
- Uploads are **idempotent on a batch identifier**, because a connection
  dropping mid-upload is routine on a poor link, and without one the proxy must
  choose between losing the batch and duplicating it.
- A proxy may only write values for items assigned to it. Otherwise one
  customer's proxy could write into another's items by guessing an identifier.

## Multi-tenancy

Every configuration table carries `tenant_id`, and the tenant comes from the
authenticated token rather than from a request parameter. In a hosted deployment
isolation has to be a property of the data, because there is only one set of
servers — and taking the tenant from the URL is how one customer reads another's
data by changing a number.

## Three bugs worth recording

Each was found by running the thing, not by reading it, and each is a class
rather than an incident.

**Enum names are not comparable.** Severity is stored as a name, and
`severity >= 'NOT_CLASSIFIED'` became a string comparison: `'HIGH'` failed it and
`'WARNING'` passed. The filter returned warnings and hid disasters, and the sort
put Warning above Disaster. Fixed with a *generated* numeric column — generated
rather than maintained in application code, because a denormalised value that
code must remember to update is one that eventually drifts, and a quietly wrong
severity filter is worse than an obviously broken one.

**Absent is not null.** The API omitted null fields, so "never collected"
arrived at the client as a missing key and every `!== null` check passed on
`undefined`. Items that had produced nothing reported "OK". Fixed on both sides:
the server serialises nulls explicitly, and the client tests for both forms.

**A wildcard permission is not a permission.** The super-admin role held `*`,
and `hasAuthority('host.read')` is an exact string match — so the most
privileged role in the system could do nothing at all. The wildcard is now
expanded into the concrete permission set once, at the point authorities are
built, rather than special-cased at every check.

## Not implemented

The schema and domain model cover these; the execution is not written.

**Low-level discovery.** Discovery rules are collected and their JSON is stored;
nothing yet instantiates prototypes from it. Needs: parse the entity list, apply
`lld_filter`, substitute macros into prototype items and triggers, create and
update the resulting objects, and remove them once an entity has been absent for
the rule's lifetime. `lld_discovered_entity` exists to track exactly that.

**Network discovery.** `discovery_rule`, `discovery_check` and `discovered_host`
exist. Needs a scanner walking the address ranges, plus the discovery-source
action operations to create and classify hosts from what it finds.

**SNMP traps, IPMI, JMX.** `CheckType` names them and the poller registry would
pick up an implementation automatically; none is written.

**Network maps.** Tables and the widget type exist; no rendering.

## Operational notes

- **`ping` must be installed.** Raw ICMP sockets need privileges the JVM cannot
  request, so the collector shells out to it. Without it every ICMP check
  degrades to a TCP probe that cannot measure loss or latency, and the camera
  wall becomes far less useful than it looks. Both images install it; the server
  and proxy need `NET_RAW`.
- **Set `NMS_JWT_SECRET`.** Absent, a key is generated at startup: sessions do
  not survive a restart and replicas reject each other's tokens. A shipped
  default was not an option — a default signing secret is an authentication
  bypass anyone can read in the source.
- **Watch the self-monitoring metrics.** History buffer depth, trigger queue
  depth and poller queue depth all rising means the database is behind. Dropped
  counts rising means data is being lost.
- **`replicas × dbPoolSize` must stay under the database's connection limit.**
  Exceeding it fails far less gracefully than a slow request.
