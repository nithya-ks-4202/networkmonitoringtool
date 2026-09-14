-- ---------------------------------------------------------------------------
-- Default tenant, roles, groups, media types and built-in templates.
--
-- No user account is seeded here. The first administrator is created at
-- startup from NMS_ADMIN_PASSWORD (or a generated one printed to the log),
-- so a shipped password hash never ends up in source control or in every
-- customer's database.
-- ---------------------------------------------------------------------------

INSERT INTO tenant (tenant_id, name, slug, history_retention_days, trend_retention_days)
VALUES (1, 'Default', 'default', 90, 730);

SELECT setval(pg_get_serial_sequence('tenant', 'tenant_id'), 1, true);

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------

INSERT INTO role (tenant_id, name, role_type, builtin, permissions) VALUES
(1, 'Super admin', 'SUPER_ADMIN', TRUE, '["*"]'::jsonb),
(1, 'Admin', 'ADMIN', TRUE, '[
    "host.read","host.write","item.read","item.write","trigger.read","trigger.write",
    "template.read","template.write","action.read","action.write","mediatype.read",
    "maintenance.read","maintenance.write","problem.read","problem.acknowledge",
    "dashboard.read","dashboard.write","map.read","map.write","discovery.read",
    "discovery.write","proxy.read","report.read"
]'::jsonb),
(1, 'Operator', 'USER', TRUE, '[
    "host.read","item.read","trigger.read","problem.read","problem.acknowledge",
    "dashboard.read","map.read","report.read"
]'::jsonb),
(1, 'Read only', 'USER', TRUE, '[
    "host.read","item.read","trigger.read","problem.read","dashboard.read","map.read","report.read"
]'::jsonb),
(1, 'Guest', 'GUEST', TRUE, '["dashboard.read","problem.read"]'::jsonb);

-- ---------------------------------------------------------------------------
-- Host groups
-- ---------------------------------------------------------------------------

INSERT INTO host_group (tenant_id, name, description) VALUES
(1, 'Templates',        'Container for template definitions'),
(1, 'Discovered hosts', 'Hosts created automatically by network discovery'),
(1, 'Network devices',  'Switches, routers, firewalls and access points'),
(1, 'Servers',          'Physical and virtual servers running the agent'),
(1, 'Cameras',          'IP cameras and video encoders'),
(1, 'Applications',     'Application and service level checks');

-- ---------------------------------------------------------------------------
-- User groups
-- ---------------------------------------------------------------------------

INSERT INTO user_group (tenant_id, name) VALUES
(1, 'Administrators'),
(1, 'Network operations'),
(1, 'No access to the frontend');

UPDATE user_group SET enabled = FALSE WHERE name = 'No access to the frontend' AND tenant_id = 1;

-- ---------------------------------------------------------------------------
-- Media types
--
-- Shipped disabled with placeholder configuration: an enabled channel with
-- wrong credentials produces a flood of delivery failures on first start.
-- ---------------------------------------------------------------------------

INSERT INTO media_type (tenant_id, name, type, status, config, templates, max_attempts) VALUES
(1, 'Email', 'EMAIL', 'DISABLED',
 '{"smtpHost":"localhost","smtpPort":25,"smtpSecurity":"NONE","fromAddress":"monitoring@example.com","fromName":"Monitoring"}'::jsonb,
 '{
   "TRIGGER.PROBLEM":  {"subject":"[{EVENT.SEVERITY}] {EVENT.NAME}",
                        "body":"Problem started at {EVENT.TIME} on {EVENT.DATE}\nHost: {HOST.NAME} ({HOST.IP})\nSeverity: {EVENT.SEVERITY}\n\n{EVENT.OPDATA}\n\nProblem ID: {EVENT.ID}"},
   "TRIGGER.RECOVERY": {"subject":"[RESOLVED] {EVENT.NAME}",
                        "body":"Problem resolved at {EVENT.RECOVERY.TIME} on {EVENT.RECOVERY.DATE}\nHost: {HOST.NAME}\nDuration: {EVENT.DURATION}\n\nProblem ID: {EVENT.ID}"},
   "TRIGGER.UPDATE":   {"subject":"[UPDATE] {EVENT.NAME}",
                        "body":"{USER.FULLNAME} {EVENT.UPDATE.ACTION} problem at {EVENT.UPDATE.TIME}\n\n{EVENT.UPDATE.MESSAGE}"}
 }'::jsonb, 3),

(1, 'Slack', 'SLACK', 'DISABLED',
 '{"webhookUrl":"","channel":"#alerts","username":"Monitoring"}'::jsonb,
 '{
   "TRIGGER.PROBLEM":  {"subject":"{EVENT.SEVERITY}: {EVENT.NAME}",
                        "body":"*{EVENT.NAME}*\nHost: {HOST.NAME} ({HOST.IP})\nSeverity: {EVENT.SEVERITY}\nStarted: {EVENT.TIME} {EVENT.DATE}\n{EVENT.OPDATA}"},
   "TRIGGER.RECOVERY": {"subject":"Resolved: {EVENT.NAME}",
                        "body":"*Resolved* {EVENT.NAME}\nHost: {HOST.NAME}\nDuration: {EVENT.DURATION}"}
 }'::jsonb, 3),

(1, 'Microsoft Teams', 'TEAMS', 'DISABLED',
 '{"webhookUrl":""}'::jsonb,
 '{
   "TRIGGER.PROBLEM":  {"subject":"{EVENT.SEVERITY}: {EVENT.NAME}",
                        "body":"Host **{HOST.NAME}** ({HOST.IP})\n\nSeverity: {EVENT.SEVERITY}\n\nStarted: {EVENT.TIME} {EVENT.DATE}"},
   "TRIGGER.RECOVERY": {"subject":"Resolved: {EVENT.NAME}",
                        "body":"Host **{HOST.NAME}** recovered after {EVENT.DURATION}"}
 }'::jsonb, 3),

(1, 'Generic webhook', 'WEBHOOK', 'DISABLED',
 '{"url":"","method":"POST","headers":{"Content-Type":"application/json"}}'::jsonb,
 '{
   "TRIGGER.PROBLEM":  {"subject":"{EVENT.NAME}",
                        "body":"{\"event\":\"problem\",\"id\":\"{EVENT.ID}\",\"name\":\"{EVENT.NAME}\",\"severity\":\"{EVENT.SEVERITY}\",\"host\":\"{HOST.NAME}\",\"ip\":\"{HOST.IP}\",\"time\":\"{EVENT.TIME}\"}"},
   "TRIGGER.RECOVERY": {"subject":"{EVENT.NAME}",
                        "body":"{\"event\":\"recovery\",\"id\":\"{EVENT.ID}\",\"name\":\"{EVENT.NAME}\",\"host\":\"{HOST.NAME}\",\"duration\":\"{EVENT.DURATION}\"}"}
 }'::jsonb, 3),

(1, 'PagerDuty', 'PAGERDUTY', 'DISABLED',
 '{"routingKey":"","apiUrl":"https://events.pagerduty.com/v2/enqueue"}'::jsonb,
 '{
   "TRIGGER.PROBLEM":  {"subject":"{EVENT.NAME}", "body":"{HOST.NAME}: {EVENT.NAME}"},
   "TRIGGER.RECOVERY": {"subject":"{EVENT.NAME}", "body":"{HOST.NAME}: resolved"}
 }'::jsonb, 3);

-- ---------------------------------------------------------------------------
-- Built-in templates
--
-- Templates are hosts with flags = 'TEMPLATE'. Items and triggers defined here
-- are copied onto each linked host, with {HOST.CONN} and {$MACRO} references
-- resolved against that host.
-- ---------------------------------------------------------------------------

INSERT INTO host (tenant_id, host, name, flags, host_class, description) VALUES
(1, 'template.icmp',        'Template: ICMP reachability',  'TEMPLATE', 'GENERIC',
    'Ping-based reachability, packet loss and round-trip time. Applies to anything with an IP address.'),
(1, 'template.camera',      'Template: IP camera',          'TEMPLATE', 'CAMERA',
    'Online/offline status for IP cameras: ICMP, RTSP stream endpoint, ONVIF device service and HTTP interface.'),
(1, 'template.snmp.device', 'Template: SNMP network device','TEMPLATE', 'NETWORK_DEVICE',
    'Generic SNMP device: uptime, system information and per-interface traffic via low-level discovery.'),
(1, 'template.linux.agent', 'Template: Linux by agent',     'TEMPLATE', 'SERVER',
    'CPU, memory, disk, network and process metrics collected through the monitoring agent.');

INSERT INTO host_group_member (host_id, group_id)
SELECT h.host_id, g.group_id
FROM host h CROSS JOIN host_group g
WHERE h.flags = 'TEMPLATE' AND h.tenant_id = 1 AND g.tenant_id = 1 AND g.name = 'Templates';

-- --- ICMP reachability -----------------------------------------------------

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, description, params)
SELECT 1, h.host_id, v.name, v.key_, v.check_type, v.value_type, v.units,
       v.delay, v.hist, v.trend, v.descr, v.params::jsonb
FROM host h,
(VALUES
 ('ICMP ping',            'icmpping',     'ICMP_PING', 'UNSIGNED', '',   60, 31, 365,
  'Reachability: 1 when the host answered at least one echo request in the last attempt, 0 otherwise.',
  '{"packets":"3","interval":"200","size":"56"}'),
 ('ICMP loss',            'icmppingloss', 'ICMP_PING', 'FLOAT',    '%',  60, 31, 365,
  'Percentage of echo requests that received no reply. Sustained partial loss usually precedes a hard failure.',
  '{"packets":"3","interval":"200","size":"56"}'),
 ('ICMP response time',   'icmppingsec',  'ICMP_PING', 'FLOAT',    's',  60, 31, 365,
  'Average round-trip time of the successful replies.',
  '{"packets":"3","interval":"200","size":"56"}')
) AS v(name, key_, check_type, value_type, units, delay, hist, trend, descr, params)
WHERE h.host = 'template.icmp' AND h.tenant_id = 1;

INSERT INTO trigger_def (tenant_id, host_id, description, expression, severity, comments, manual_close)
SELECT 1, h.host_id, v.descr, v.expr, v.sev, v.comment, FALSE
FROM host h,
(VALUES
 ('Host is unreachable (no ICMP response for 3 minutes)',
  'max(/template.icmp/icmpping,3m)=0', 'HIGH',
  'Three consecutive minutes with no echo reply. max() over the window rather than last() so a single dropped packet does not page anyone.'),
 ('High ICMP packet loss (>20% for 5 minutes)',
  'min(/template.icmp/icmppingloss,5m)>20', 'WARNING',
  'Sustained loss above 20%. min() over the window means every sample in the period exceeded the threshold.'),
 ('High ICMP response time (>0.15s for 5 minutes)',
  'min(/template.icmp/icmppingsec,5m)>0.15', 'INFORMATION',
  'Latency degradation, often the first visible symptom of a saturated uplink.')
) AS v(descr, expr, sev, comment)
WHERE h.host = 'template.icmp' AND h.tenant_id = 1;

-- --- IP camera -------------------------------------------------------------

INSERT INTO host_macro (host_id, macro, value, description)
SELECT h.host_id, v.macro, v.value, v.descr
FROM host h,
(VALUES
 ('{$CAMERA.RTSP.PORT}', '554',        'RTSP control port'),
 ('{$CAMERA.RTSP.PATH}', '/Streaming/Channels/101', 'Stream path probed by the RTSP OPTIONS check'),
 ('{$CAMERA.HTTP.PORT}', '80',         'Web interface port'),
 ('{$CAMERA.ONVIF.PORT}','80',         'ONVIF device service port'),
 ('{$CAMERA.USER}',      '',           'Username for RTSP/ONVIF authentication'),
 ('{$CAMERA.PASSWORD}',  '',           'Password for RTSP/ONVIF authentication'),
 ('{$CAMERA.DOWN.TIME}', '3m',         'How long a camera must be unreachable before it is declared offline')
) AS v(macro, value, descr)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, timeout_seconds, description, params)
SELECT 1, h.host_id, v.name, v.key_, v.check_type, v.value_type, v.units,
       v.delay, v.hist, v.trend, v.tmo, v.descr, v.params::jsonb
FROM host h,
(VALUES
 ('Camera ICMP ping', 'icmpping', 'ICMP_PING', 'UNSIGNED', '', 60, 31, 365, 3,
  'Network-layer reachability of the camera.',
  '{"packets":"3","interval":"200","size":"56"}'),
 ('Camera ICMP response time', 'icmppingsec', 'ICMP_PING', 'FLOAT', 's', 60, 31, 365, 3,
  'Round-trip time to the camera.',
  '{"packets":"3","interval":"200","size":"56"}'),
 ('RTSP stream available', 'net.tcp.service[rtsp]', 'RTSP', 'UNSIGNED', '', 60, 31, 365, 5,
  'Sends an RTSP OPTIONS request to the stream URL. This is the check that matters: a camera that pings but will not negotiate RTSP is recording nothing.',
  '{"port":"{$CAMERA.RTSP.PORT}","path":"{$CAMERA.RTSP.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),
 ('RTSP response time', 'net.tcp.service.perf[rtsp]', 'RTSP', 'FLOAT', 's', 60, 31, 365, 5,
  'Time taken to complete the RTSP OPTIONS handshake.',
  '{"port":"{$CAMERA.RTSP.PORT}","path":"{$CAMERA.RTSP.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}","perf":"true"}'),
 ('HTTP interface available', 'net.tcp.service[http]', 'TCP_PORT', 'UNSIGNED', '', 120, 31, 365, 5,
  'TCP reachability of the camera web interface.',
  '{"port":"{$CAMERA.HTTP.PORT}"}'),
 ('ONVIF device reachable', 'onvif.device.status', 'ONVIF', 'UNSIGNED', '', 300, 31, 365, 8,
  'Calls GetSystemDateAndTime on the ONVIF device service. Unauthenticated by specification, so it works before credentials are configured.',
  '{"port":"{$CAMERA.ONVIF.PORT}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),
 ('ONVIF device information', 'onvif.device.info', 'ONVIF', 'TEXT', '', 3600, 7, 0, 8,
  'Manufacturer, model, firmware and serial number, used to populate host inventory.',
  '{"port":"{$CAMERA.ONVIF.PORT}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}","operation":"GetDeviceInformation"}')
) AS v(name, key_, check_type, value_type, units, delay, hist, trend, tmo, descr, params)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

INSERT INTO trigger_def (tenant_id, host_id, description, expression, severity, comments, manual_close, opdata)
SELECT 1, h.host_id, v.descr, v.expr, v.sev, v.comment, FALSE, v.opdata
FROM host h,
(VALUES
 ('Camera is OFFLINE',
  'max(/template.camera/icmpping,{$CAMERA.DOWN.TIME})=0', 'HIGH',
  'The camera has not answered ICMP for the configured window. Check power over Ethernet, the switch port and the network path before the camera itself.',
  'Last response: {ITEM.LASTVALUE1}'),
 ('Camera stream is DOWN (RTSP not responding)',
  'max(/template.camera/net.tcp.service[rtsp],3m)=0 and max(/template.camera/icmpping,3m)=1', 'HIGH',
  'The camera is reachable on the network but is not serving RTSP. Typically a hung encoder or an exhausted stream-session limit; a reboot usually clears it.',
  'RTSP port {$CAMERA.RTSP.PORT}'),
 ('Camera web interface is unreachable',
  'max(/template.camera/net.tcp.service[http],5m)=0 and max(/template.camera/icmpping,5m)=1', 'WARNING',
  'The HTTP interface is not accepting connections while the camera still answers ping.',
  ''),
 ('Camera ONVIF service is not responding',
  'max(/template.camera/onvif.device.status,10m)=0 and max(/template.camera/icmpping,10m)=1', 'WARNING',
  'ONVIF is unavailable, so the camera cannot be managed or re-provisioned even though it may still be streaming.',
  ''),
 ('Camera response time is degraded',
  'min(/template.camera/icmppingsec,10m)>0.3', 'INFORMATION',
  'Elevated latency to the camera, often caused by a saturated access switch uplink.',
  'RTT: {ITEM.LASTVALUE1}')
) AS v(descr, expr, sev, comment, opdata)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

-- The stream, web and ONVIF triggers are meaningless while the camera is
-- offline, so they depend on the offline trigger and stay suppressed when it
-- fires. Without this, one dead camera raises four separate problems.
INSERT INTO trigger_dependency (trigger_id, depends_on_trigger_id)
SELECT dependent.trigger_id, master.trigger_id
FROM trigger_def dependent
JOIN trigger_def master
  ON master.host_id = dependent.host_id
 AND master.description = 'Camera is OFFLINE'
JOIN host h ON h.host_id = dependent.host_id
WHERE h.host = 'template.camera'
  AND dependent.description IN (
      'Camera stream is DOWN (RTSP not responding)',
      'Camera web interface is unreachable',
      'Camera ONVIF service is not responding',
      'Camera response time is degraded');

INSERT INTO trigger_tag (trigger_id, tag, value)
SELECT t.trigger_id, 'scope', 'availability'
FROM trigger_def t JOIN host h ON h.host_id = t.host_id
WHERE h.host = 'template.camera';

INSERT INTO trigger_tag (trigger_id, tag, value)
SELECT t.trigger_id, 'class', 'camera'
FROM trigger_def t JOIN host h ON h.host_id = t.host_id
WHERE h.host = 'template.camera';

-- --- SNMP network device ---------------------------------------------------

INSERT INTO host_macro (host_id, macro, value, description)
SELECT h.host_id, v.macro, v.value, v.descr
FROM host h,
(VALUES
 ('{$SNMP.COMMUNITY}',     'public', 'SNMP v1/v2c read community'),
 ('{$IF.UTIL.MAX}',        '90',     'Interface utilisation percentage that raises a warning'),
 ('{$IF.ERRORS.WARN}',     '2',      'Interface errors per second that raises a warning'),
 ('{$SNMP.TIMEOUT}',       '5m',     'How long SNMP may be unavailable before alerting')
) AS v(macro, value, descr)
WHERE h.host = 'template.snmp.device' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, description, params)
SELECT 1, h.host_id, v.name, v.key_, v.check_type, v.value_type, v.units,
       v.delay, v.hist, v.trend, v.descr, v.params::jsonb
FROM host h,
(VALUES
 ('Uptime', 'system.uptime', 'SNMP', 'UNSIGNED', 'uptime', 60, 31, 365,
  'sysUpTime. A value lower than the previous one means the device restarted.',
  '{"oid":"1.3.6.1.2.1.1.3.0"}'),
 ('System name', 'system.name', 'SNMP', 'CHARACTER', '', 3600, 31, 0,
  'sysName, the configured hostname of the device.',
  '{"oid":"1.3.6.1.2.1.1.5.0"}'),
 ('System description', 'system.descr', 'SNMP', 'CHARACTER', '', 3600, 31, 0,
  'sysDescr, usually model and firmware version.',
  '{"oid":"1.3.6.1.2.1.1.1.0"}'),
 ('System location', 'system.location', 'SNMP', 'CHARACTER', '', 3600, 31, 0,
  'sysLocation.',
  '{"oid":"1.3.6.1.2.1.1.6.0"}'),
 ('System contact', 'system.contact', 'SNMP', 'CHARACTER', '', 3600, 31, 0,
  'sysContact.',
  '{"oid":"1.3.6.1.2.1.1.4.0"}'),
 ('SNMP availability', 'snmp.available', 'INTERNAL', 'UNSIGNED', '', 60, 31, 365,
  'Whether the last SNMP poll of this host succeeded.',
  '{}'),
 ('Number of interfaces', 'net.if.count', 'SNMP', 'UNSIGNED', '', 3600, 31, 365,
  'ifNumber.',
  '{"oid":"1.3.6.1.2.1.2.1.0"}')
) AS v(name, key_, check_type, value_type, units, delay, hist, trend, descr, params)
WHERE h.host = 'template.snmp.device' AND h.tenant_id = 1;

-- Low-level discovery of network interfaces. The rule item returns a JSON
-- array of {#IFNAME}/{#IFINDEX} pairs; prototypes below become real items.
-- lldMacros is a flat "macro=source" list rather than a nested object: item
-- params are a string map, and the SNMP walker parses this form directly.
-- "value" binds the column's value, "index" binds the row's OID suffix -- which
-- is what later lets a prototype build ...1.3.6.1.2.1.2.2.1.10.{#IFINDEX} per port.
INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type,
                  delay_seconds, flags, description, params)
SELECT 1, h.host_id, 'Network interface discovery', 'net.if.discovery', 'SNMP', 'TEXT',
       3600, 'DISCOVERY_RULE',
       'Walks the IF-MIB interface table and creates traffic and status items per interface.',
       '{"oid":"1.3.6.1.2.1.2.2.1.2","lldMacros":"{#IFNAME}=value,{#IFINDEX}=index","walk":"true"}'::jsonb
FROM host h WHERE h.host = 'template.snmp.device' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, flags, description, params)
SELECT 1, h.host_id, v.name, v.key_, 'SNMP', v.value_type, v.units,
       v.delay, 31, 365, 'PROTOTYPE', v.descr, v.params::jsonb
FROM host h,
(VALUES
 ('Interface {#IFNAME}: Bits received', 'net.if.in[{#IFNAME}]', 'UNSIGNED', 'bps', 60,
  'ifHCInOctets, converted to bits per second by this item''s preprocessing steps.',
  '{"oid":"1.3.6.1.2.1.31.1.1.1.6.{#IFINDEX}"}'),
 ('Interface {#IFNAME}: Bits sent', 'net.if.out[{#IFNAME}]', 'UNSIGNED', 'bps', 60,
  'ifHCOutOctets, converted to bits per second.',
  '{"oid":"1.3.6.1.2.1.31.1.1.1.10.{#IFINDEX}"}'),
 ('Interface {#IFNAME}: Inbound errors', 'net.if.in.errors[{#IFNAME}]', 'UNSIGNED', '', 60,
  'ifInErrors as a rate.',
  '{"oid":"1.3.6.1.2.1.2.2.1.14.{#IFINDEX}"}'),
 ('Interface {#IFNAME}: Outbound errors', 'net.if.out.errors[{#IFNAME}]', 'UNSIGNED', '', 60,
  'ifOutErrors as a rate.',
  '{"oid":"1.3.6.1.2.1.2.2.1.20.{#IFINDEX}"}'),
 ('Interface {#IFNAME}: Operational status', 'net.if.status[{#IFNAME}]', 'UNSIGNED', '', 60,
  'ifOperStatus: 1 up, 2 down, 3 testing.',
  '{"oid":"1.3.6.1.2.1.2.2.1.8.{#IFINDEX}"}'),
 ('Interface {#IFNAME}: Speed', 'net.if.speed[{#IFNAME}]', 'UNSIGNED', 'bps', 3600,
  'ifHighSpeed in megabits, scaled to bits per second.',
  '{"oid":"1.3.6.1.2.1.31.1.1.1.15.{#IFINDEX}"}')
) AS v(name, key_, value_type, units, delay, descr, params)
WHERE h.host = 'template.snmp.device' AND h.tenant_id = 1;

-- Preprocessing belongs in item_preprocessing, not in an item parameter: the
-- pipeline reads this table, so a step declared anywhere else would never run
-- and these items would store raw cumulative octet counters under a label that
-- says "bps".
--
-- Two steps on the traffic items, in order: the counter becomes a per-second
-- rate, then bytes become bits. CHANGE_PER_SECOND also absorbs the counter
-- wrapping that would otherwise produce one enormous spike per rollover.
INSERT INTO item_preprocessing (item_id, step, type, params, error_handler)
SELECT i.item_id, 1, 'CHANGE_PER_SECOND', '[]'::jsonb,
       -- The first sample has no predecessor to subtract, and that is normal
       -- rather than an error worth showing an operator.
       'DISCARD_VALUE'
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE h.host = 'template.snmp.device'
  AND i.key_ IN ('net.if.in[{#IFNAME}]', 'net.if.out[{#IFNAME}]',
                 'net.if.in.errors[{#IFNAME}]', 'net.if.out.errors[{#IFNAME}]');

INSERT INTO item_preprocessing (item_id, step, type, params, error_handler)
SELECT i.item_id, 2, 'MULTIPLIER', '["8"]'::jsonb, 'ERROR'
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE h.host = 'template.snmp.device'
  AND i.key_ IN ('net.if.in[{#IFNAME}]', 'net.if.out[{#IFNAME}]');

INSERT INTO item_preprocessing (item_id, step, type, params, error_handler)
SELECT i.item_id, 1, 'MULTIPLIER', '["1000000"]'::jsonb, 'ERROR'
FROM item i JOIN host h ON h.host_id = i.host_id
WHERE h.host = 'template.snmp.device' AND i.key_ = 'net.if.speed[{#IFNAME}]';

-- sysUpTime is reported in seconds by the SNMP poller, so no scaling step is
-- needed here; the uptime trigger reads seconds directly.

INSERT INTO trigger_def (tenant_id, host_id, description, expression, severity, comments)
SELECT 1, h.host_id, v.descr, v.expr, v.sev, v.comment
FROM host h,
(VALUES
 ('Device has restarted (uptime < 10m)',
  'last(/template.snmp.device/system.uptime)<600', 'INFORMATION',
  'sysUpTime went backwards or is very low, meaning the device rebooted.'),
 ('No SNMP data collected',
  'max(/template.snmp.device/snmp.available,{$SNMP.TIMEOUT})=0', 'AVERAGE',
  'SNMP has stopped answering. Check the community string, ACLs and the agent on the device.')
) AS v(descr, expr, sev, comment)
WHERE h.host = 'template.snmp.device' AND h.tenant_id = 1;

-- --- Linux by agent --------------------------------------------------------

INSERT INTO host_macro (host_id, macro, value, description)
SELECT h.host_id, v.macro, v.value, v.descr
FROM host h,
(VALUES
 ('{$CPU.UTIL.CRIT}',  '90', 'CPU utilisation percentage considered critical'),
 ('{$MEM.AVAIL.MIN}',  '10', 'Minimum available memory percentage'),
 ('{$VFS.FS.PUSED.MAX}','90','Filesystem used percentage that raises a warning'),
 ('{$LOAD.AVG.PER.CPU.MAX}','2','Maximum 5-minute load average per CPU core')
) AS v(macro, value, descr)
WHERE h.host = 'template.linux.agent' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, description, params)
SELECT 1, h.host_id, v.name, v.key_, 'AGENT_PASSIVE', v.value_type, v.units,
       v.delay, 31, 365, v.descr, '{}'::jsonb
FROM host h,
(VALUES
 ('Agent ping',              'agent.ping',                'UNSIGNED', '',      60,
  'Returns 1 when the agent answers. Becomes unsupported when it does not, which is what the "agent unreachable" trigger detects.'),
 ('Agent version',           'agent.version',             'CHARACTER','',      3600,
  'Version string of the running agent.'),
 ('Host name',               'system.hostname',           'CHARACTER','',      3600, 'Hostname reported by the operating system.'),
 ('System uptime',           'system.uptime',             'UNSIGNED', 'uptime',60,  'Seconds since boot.'),
 ('Number of CPUs',          'system.cpu.num',            'UNSIGNED', '',      3600,'Logical CPU count, used to normalise load average.'),
 ('CPU utilisation',         'system.cpu.util',           'FLOAT',    '%',     60,  'Total CPU utilisation across all cores.'),
 ('CPU idle time',           'system.cpu.util[,idle]',    'FLOAT',    '%',     60,  'Percentage of time the CPU was idle.'),
 ('CPU iowait time',         'system.cpu.util[,iowait]',  'FLOAT',    '%',     60,
  'Time spent waiting on I/O. Sustained high iowait with low utilisation points at storage, not compute.'),
 ('CPU steal time',          'system.cpu.util[,steal]',   'FLOAT',    '%',     60,
  'Cycles taken by the hypervisor. Non-zero steal on a VM means the host is oversubscribed.'),
 ('Load average (1m)',       'system.cpu.load[all,avg1]', 'FLOAT',    '',      60,  'One-minute load average.'),
 ('Load average (5m)',       'system.cpu.load[all,avg5]', 'FLOAT',    '',      60,  'Five-minute load average.'),
 ('Load average (15m)',      'system.cpu.load[all,avg15]','FLOAT',    '',      60,  'Fifteen-minute load average.'),
 ('Total memory',            'vm.memory.size[total]',     'UNSIGNED', 'B',     3600,'Total physical memory.'),
 ('Available memory',        'vm.memory.size[available]', 'UNSIGNED', 'B',     60,  'Memory available to new allocations, including reclaimable cache.'),
 ('Available memory %',      'vm.memory.size[pavailable]','FLOAT',    '%',     60,  'Available memory as a percentage of total.'),
 ('Total swap',              'system.swap.size[,total]',  'UNSIGNED', 'B',     3600,'Total swap space.'),
 ('Free swap %',             'system.swap.size[,pfree]',  'FLOAT',    '%',     60,  'Free swap as a percentage.'),
 ('Number of processes',     'proc.num[]',                'UNSIGNED', '',      60,  'Total process count.'),
 ('Number of running processes','proc.num[,,run]',        'UNSIGNED', '',      60,  'Processes in the running state.'),
 ('Number of logged in users','system.users.num',         'UNSIGNED', '',      300, 'Interactive login sessions.')
) AS v(name, key_, value_type, units, delay, descr)
WHERE h.host = 'template.linux.agent' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type,
                  delay_seconds, flags, description, params)
SELECT 1, h.host_id, v.name, v.key_, 'AGENT_PASSIVE', 'TEXT', 3600, 'DISCOVERY_RULE', v.descr, '{}'::jsonb
FROM host h,
(VALUES
 ('Mounted filesystem discovery', 'vfs.fs.discovery',
  'Returns the mounted filesystems as {#FSNAME}/{#FSTYPE} pairs.'),
 ('Network interface discovery',  'net.if.discovery',
  'Returns the network interfaces as {#IFNAME}.')
) AS v(name, key_, descr)
WHERE h.host = 'template.linux.agent' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, flags, description, params)
SELECT 1, h.host_id, v.name, v.key_, 'AGENT_PASSIVE', v.value_type, v.units,
       60, 31, 365, 'PROTOTYPE', v.descr, '{}'::jsonb
FROM host h,
(VALUES
 ('{#FSNAME}: Total space',  'vfs.fs.size[{#FSNAME},total]', 'UNSIGNED','B','Filesystem capacity.'),
 ('{#FSNAME}: Used space',   'vfs.fs.size[{#FSNAME},used]',  'UNSIGNED','B','Bytes in use.'),
 ('{#FSNAME}: Used space %', 'vfs.fs.size[{#FSNAME},pused]', 'FLOAT',   '%','Used percentage, the value most filesystem alerts key off.'),
 ('{#FSNAME}: Free inodes %','vfs.fs.inode[{#FSNAME},pfree]','FLOAT',   '%','Free inode percentage. A filesystem can exhaust inodes with space still free.'),
 ('{#IFNAME}: Bits received','net.if.in[{#IFNAME}]',         'UNSIGNED','bps','Inbound throughput.'),
 ('{#IFNAME}: Bits sent',    'net.if.out[{#IFNAME}]',        'UNSIGNED','bps','Outbound throughput.')
) AS v(name, key_, value_type, units, descr)
WHERE h.host = 'template.linux.agent' AND h.tenant_id = 1;

INSERT INTO trigger_def (tenant_id, host_id, description, expression, severity, comments)
SELECT 1, h.host_id, v.descr, v.expr, v.sev, v.comment
FROM host h,
(VALUES
 ('Agent is not available',
  'nodata(/template.linux.agent/agent.ping,5m)=1', 'AVERAGE',
  'No agent response for five minutes. Distinguishes an agent or network failure from a host that is genuinely down -- pair it with the ICMP template to tell them apart.'),
 ('Host has just restarted (uptime < 10m)',
  'last(/template.linux.agent/system.uptime)<600', 'INFORMATION',
  'An unplanned restart is worth knowing about even when everything recovered.'),
 ('High CPU utilisation (>{$CPU.UTIL.CRIT}% for 5m)',
  'min(/template.linux.agent/system.cpu.util,5m)>{$CPU.UTIL.CRIT}', 'WARNING',
  'Every sample in the last five minutes exceeded the threshold, so this is load rather than a spike.'),
 ('High CPU iowait (>20% for 5m)',
  'min(/template.linux.agent/system.cpu.util[,iowait],5m)>20', 'WARNING',
  'The CPU is blocked on storage. Look at disk latency before adding CPU.'),
 ('Load average per core is high',
  'min(/template.linux.agent/system.cpu.load[all,avg5],5m)/last(/template.linux.agent/system.cpu.num)>{$LOAD.AVG.PER.CPU.MAX}', 'WARNING',
  'Load normalised by core count, so the threshold means the same thing on a 2-core and a 64-core machine.'),
 ('Low available memory (<{$MEM.AVAIL.MIN}%)',
  'max(/template.linux.agent/vm.memory.size[pavailable],5m)<{$MEM.AVAIL.MIN}', 'AVERAGE',
  'Available memory rather than free memory, so reclaimable page cache is not counted as pressure.'),
 ('Swap space is low (<20% free)',
  'max(/template.linux.agent/system.swap.size[,pfree],5m)<20 and last(/template.linux.agent/system.swap.size[,total])>0', 'WARNING',
  'Guarded on total swap so hosts configured without swap do not alert permanently.')
) AS v(descr, expr, sev, comment)
WHERE h.host = 'template.linux.agent' AND h.tenant_id = 1;

-- ---------------------------------------------------------------------------
-- Default action: notify administrators of high-severity problems
--
-- Disabled on install. Enabling an action that has no configured media type
-- only generates failed alerts.
-- ---------------------------------------------------------------------------

INSERT INTO action (tenant_id, name, event_source, status, escalation_period_seconds,
                    pause_during_maintenance, pause_on_acknowledge, notify_on_recovery, description)
VALUES (1, 'Notify administrators of high severity problems', 'TRIGGER', 'DISABLED', 1800,
        TRUE, TRUE, TRUE,
        'Escalation ladder: notify on-call immediately, repeat after 30 minutes, then widen to the administrators group.');

INSERT INTO action_condition (action_id, label, condition_type, operator, value)
SELECT a.action_id, 'A', 'TRIGGER_SEVERITY', 'GREATER_OR_EQUAL', 'HIGH'
FROM action a WHERE a.name = 'Notify administrators of high severity problems' AND a.tenant_id = 1;

INSERT INTO action_operation (action_id, operation_type, step_from, step_to, media_type_id)
SELECT a.action_id, 'SEND_MESSAGE', v.step_from, v.step_to,
       (SELECT media_type_id FROM media_type WHERE name = 'Email' AND tenant_id = 1)
FROM action a, (VALUES (1, 2), (3, 0)) AS v(step_from, step_to)
WHERE a.name = 'Notify administrators of high severity problems' AND a.tenant_id = 1;

INSERT INTO action_operation_group (operation_id, usrgrp_id)
SELECT o.operation_id, g.usrgrp_id
FROM action_operation o
JOIN action a ON a.action_id = o.action_id
CROSS JOIN user_group g
WHERE a.name = 'Notify administrators of high severity problems'
  AND g.name = 'Administrators' AND g.tenant_id = 1;

-- ---------------------------------------------------------------------------
-- Default dashboard
-- ---------------------------------------------------------------------------

INSERT INTO dashboard (tenant_id, name, private, auto_refresh_seconds)
VALUES (1, 'Global view', FALSE, 30);

INSERT INTO dashboard_widget (dashboard_id, type, name, pos_x, pos_y, width, height, config)
SELECT d.dashboard_id, v.type, v.name, v.x, v.y, v.w, v.h, v.config::jsonb
FROM dashboard d,
(VALUES
 ('PROBLEM_SEVERITY', 'Problems by severity', 0,  0, 8,  4, '{}'),
 ('HOST_AVAILABILITY','Host availability',    8,  0, 8,  4, '{}'),
 ('SYSTEM_INFO',      'System information',   16, 0, 8,  4, '{}'),
 ('PROBLEMS',         'Current problems',     0,  4, 16, 8, '{"showTags":3,"sortField":"severity","limit":50}'),
 ('CAMERA_WALL',      'Camera status',        16, 4, 8,  8, '{"groupName":"Cameras","itemKey":"icmpping"}')
) AS v(type, name, x, y, w, h, config)
WHERE d.name = 'Global view' AND d.tenant_id = 1;
