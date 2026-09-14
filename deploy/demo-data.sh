#!/usr/bin/env bash
#
# Populates a running server with a small, realistic estate so the interface can
# be looked at without waiting for real devices to be configured and polled.
#
# Creates cameras in several states -- including one whose collection is failing
# rather than whose camera is down, because telling those two apart is the point
# of the camera wall -- plus a switch, a server, some CPU history with a spike in
# it, and open problems across several severities.
#
# Usage: deploy/demo-data.sh [base-url] [username] [password]
#
# Requires psql for the history and problem rows: those describe things that
# happened in the past, and there is deliberately no API for inventing history.

set -euo pipefail

BASE="${1:-http://localhost:8080}"
USERNAME="${2:-admin}"
PASSWORD="${3:-${NMS_ADMIN_PASSWORD:-}}"

if [[ -z "$PASSWORD" ]]; then
  echo "No password given. Pass one as the third argument or set NMS_ADMIN_PASSWORD." >&2
  exit 1
fi

echo "Signing in to $BASE as $USERNAME"
TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" |
  python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

create_host() {
  local payload="$1"
  local name
  name=$(python3 -c "import sys,json; print(json.loads(sys.argv[1])['host'])" "$payload")
  local status
  status=$(curl -sS -o /tmp/nms-create-host.json -w '%{http_code}' \
    -X POST "$BASE/api/hosts" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$payload")

  case "$status" in
    201) echo "  created $name" ;;
    400) echo "  skipped $name (already exists)" ;;
    *)   echo "  FAILED  $name (HTTP $status): $(cat /tmp/nms-create-host.json)" >&2 ;;
  esac
}

camera() {
  create_host "$(cat <<JSON
{"host":"$1","name":"$2","hostClass":"CAMERA","groups":["Cameras"],
 "tags":[{"tag":"site","value":"$3"}],
 "interfaces":[
   {"type":"AGENT","main":true,"useIp":true,"ip":"$4","port":10150},
   {"type":"RTSP","main":true,"useIp":true,"ip":"$4","port":554}],
 "templates":["template.camera"]}
JSON
  )"
}

echo "Creating hosts"
camera cam-lobby-01   "Lobby camera 1"      hq      10.20.30.41
camera cam-lobby-02   "Lobby camera 2"      hq      10.20.30.42
camera cam-carpark-01 "Car park north"      hq      10.20.30.51
camera cam-carpark-02 "Car park south"      hq      10.20.30.52
camera cam-dock-01    "Loading dock"        hq      10.20.30.61
camera cam-stairs-03  "Stairwell 3"         hq      10.20.30.71
camera cam-branch-01  "Branch office front" bristol 10.40.10.11

create_host '{"host":"sw-core-01","name":"Core switch 01","hostClass":"NETWORK_DEVICE",
 "groups":["Network devices"],
 "interfaces":[{"type":"SNMP","main":true,"useIp":true,"ip":"10.20.0.1","port":161,
                "snmpVersion":"V2C","snmpCommunity":"public"}],
 "templates":["template.snmp.device"]}'

create_host '{"host":"app-web-01","name":"Web server 01","hostClass":"SERVER",
 "groups":["Servers"],
 "interfaces":[{"type":"AGENT","main":true,"useIp":true,"ip":"10.20.1.10","port":10150}],
 "templates":["template.linux.agent"]}'

if ! command -v psql >/dev/null 2>&1; then
  echo "psql not found; skipping history and problem rows."
  exit 0
fi

echo "Inserting collected values, history and problems"
psql -q -v ON_ERROR_STOP=1 <<'SQL'
-- Camera reachability: most up, two down.
INSERT INTO item_latest (item_id, clock, value_num, state, error)
SELECT i.item_id, now() - interval '30 seconds',
       CASE WHEN h.host IN ('cam-carpark-02','cam-dock-01') THEN 0 ELSE 1 END, 'NORMAL', ''
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE i.key_ = 'icmpping' AND h.host_class = 'CAMERA' AND h.host <> 'cam-branch-01'
ON CONFLICT (item_id) DO UPDATE
   SET clock = EXCLUDED.clock, value_num = EXCLUDED.value_num,
       state = 'NORMAL', error = '';

-- One camera whose collection itself is failing. The wall has to show this as
-- "no data" rather than "offline": we do not know whether that camera is fine.
INSERT INTO item_latest (item_id, clock, state, error)
SELECT i.item_id, now() - interval '20 minutes', 'NOT_SUPPORTED',
       'no route to host 10.40.10.11'
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE i.key_ = 'icmpping' AND h.host = 'cam-branch-01'
ON CONFLICT (item_id) DO UPDATE
   SET clock = EXCLUDED.clock, state = 'NOT_SUPPORTED', error = EXCLUDED.error;

-- The server writes a collection failure to both item_latest and item, so the
-- demo has to as well: views that show the reason read it from the item.
UPDATE item SET state = 'NOT_SUPPORTED', error = 'no route to host 10.40.10.11'
WHERE key_ = 'icmpping'
  AND host_id = (SELECT host_id FROM host WHERE host = 'cam-branch-01');

INSERT INTO item_latest (item_id, clock, value_num, state, error)
SELECT i.item_id, now(), 0.0042, 'NORMAL', ''
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE i.key_ = 'icmppingsec' AND h.host_class = 'CAMERA'
ON CONFLICT (item_id) DO UPDATE
   SET clock = EXCLUDED.clock, value_num = EXCLUDED.value_num;

-- Six hours of CPU history with a sustained spike in the middle, so the
-- min-to-max band shows something a single averaged line would hide.
INSERT INTO history_float (item_id, clock, value)
SELECT i.item_id,
       now() - (n || ' minutes')::interval,
       greatest(2, least(99, 28 + 14 * sin(n / 22.0) + (random() * 9)
                              + CASE WHEN n BETWEEN 150 AND 195 THEN 46 ELSE 0 END))
FROM item i JOIN host h ON h.host_id = i.host_id, generate_series(0, 360) n
WHERE i.key_ = 'system.cpu.util' AND h.host = 'app-web-01'
ON CONFLICT (item_id, clock) DO NOTHING;

INSERT INTO item_latest (item_id, clock, value_num, state, error)
SELECT i.item_id, now(), 31.4, 'NORMAL', ''
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE i.key_ = 'system.cpu.util' AND h.host = 'app-web-01'
ON CONFLICT (item_id) DO UPDATE
   SET clock = EXCLUDED.clock, value_num = EXCLUDED.value_num;

-- Open problems across several severities, one already acknowledged.
WITH raised AS (
  INSERT INTO event (tenant_id, source, object_type, object_id, clock, value, severity, name)
  SELECT 1, 'TRIGGER', 'TRIGGER', t.trigger_id,
         now() - (v.age || ' minutes')::interval, 'PROBLEM', v.sev, t.description
  FROM (VALUES
      ('cam-carpark-02', 'Camera is OFFLINE',                                  'HIGH',         47),
      ('cam-dock-01',    'Camera is OFFLINE',                                  'HIGH',         12),
      ('cam-stairs-03',  'Camera stream is DOWN (RTSP not responding)',        'HIGH',        134),
      ('sw-core-01',     'No SNMP data collected',                             'AVERAGE',       8),
      ('app-web-01',     'High CPU utilisation (>{$CPU.UTIL.CRIT}% for 5m)',   'WARNING',      63),
      ('cam-lobby-02',   'Camera response time is degraded',                   'INFORMATION', 220)
  ) AS v(hostname, descr, sev, age)
  JOIN host h ON h.host = v.hostname
  JOIN trigger_def t ON t.host_id = h.host_id AND t.description = v.descr
  RETURNING event_id, object_id, clock, severity, name
)
INSERT INTO problem (tenant_id, event_id, source, object_type, object_id, host_id,
                     clock, name, severity, original_severity, acknowledged)
SELECT 1, r.event_id, 'TRIGGER', 'TRIGGER', r.object_id, t.host_id,
       r.clock, r.name, r.severity, r.severity, r.name LIKE 'High CPU%'
FROM raised r JOIN trigger_def t ON t.trigger_id = r.object_id;

INSERT INTO problem_tag (problem_id, tag, value)
SELECT p.problem_id, 'scope', 'availability'
FROM problem p WHERE p.name LIKE 'Camera%';
SQL

echo "Done."
