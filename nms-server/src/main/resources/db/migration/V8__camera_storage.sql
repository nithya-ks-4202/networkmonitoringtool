-- ---------------------------------------------------------------------------
-- Camera storage: is the SD card there, healthy, and recording.
--
-- The gap this closes is the one the rest of the camera template was built
-- around, one layer deeper. A camera whose SD card has died answers ICMP,
-- negotiates RTSP, serves live video and reports itself healthy over ONVIF --
-- and records nothing. Live view is perfect; there is simply no footage. It is
-- normally discovered weeks later, when someone asks for an incident recording
-- and there is none, which is the exact moment monitoring was supposed to
-- have already spoken.
--
-- Read from the vendor's own API, because no interoperable standard reports
-- it: ONVIF's storage configuration describes configured targets rather than
-- card health, and its recording search service is optional and widely absent
-- on fixed cameras. Hikvision ISAPI is what is implemented.
-- ---------------------------------------------------------------------------

INSERT INTO host_macro (host_id, macro, value, description)
SELECT h.host_id, v.macro, v.value, v.descr
FROM host h,
(VALUES
 ('{$CAMERA.STORAGE.PATH}', '/ISAPI/ContentMgmt/Storage',
  'Vendor storage API path. The default is Hikvision ISAPI.'),
 ('{$CAMERA.STORAGE.PFREE.MIN}', '5',
  'Free space percentage below which the card is considered full')
) AS v(macro, value, descr)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

INSERT INTO item (tenant_id, host_id, name, key_, check_type, value_type, units,
                  delay_seconds, history_days, trend_days, timeout_seconds,
                  description, params)
SELECT 1, h.host_id, v.name, v.key_, v.check_type, v.value_type, v.units,
       v.delay, v.hist, v.trend, v.tmo, v.descr, v.params::jsonb
FROM host h,
(VALUES
 -- Five minutes, not one. A card does not fail between polls in a way that
 -- matters, and a camera's embedded web server is not a web server: asking it
 -- more often than this is a real load on the device. The collector caches one
 -- fetch across all five items, so this costs one request per camera per
 -- five minutes.
 ('Camera storage healthy', 'camera.storage.status', 'CAMERA_STORAGE', 'UNSIGNED', '',
  300, 31, 365, 10,
  'Whether every storage device the camera reports is present, writable and not in an error state. This is the check that catches a camera which streams perfectly and records nothing.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),

 -- Its own item because an absent card is not an unhealthy card: it is simply
 -- not listed, and a status check alone would have nothing to look at.
 ('Camera storage devices', 'camera.storage.count', 'CAMERA_STORAGE', 'UNSIGNED', '',
  300, 31, 365, 10,
  'How many storage devices the camera reports. Zero means no card is fitted or the card has dropped off the bus entirely, which is how a worn or counterfeit SD card usually fails.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),

 ('Camera storage total', 'camera.storage.total', 'CAMERA_STORAGE', 'UNSIGNED', 'B',
  3600, 31, 365, 10,
  'Total capacity of the camera''s storage.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),

 ('Camera storage free', 'camera.storage.free', 'CAMERA_STORAGE', 'UNSIGNED', 'B',
  300, 31, 365, 10,
  'Free space on the camera''s storage.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),

 ('Camera storage free %', 'camera.storage.pfree', 'CAMERA_STORAGE', 'FLOAT', '%',
  300, 31, 365, 10,
  'Free space as a percentage. Reported as unsupported rather than zero when no card is present, so that a missing card cannot fire the "card full" trigger.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}'),

 -- Polled as often as the status it explains, not hourly. It is what the
 -- alert quotes and what an operator reads first, so an hourly copy would be
 -- describing a healthy card at the moment the problem opens. It costs
 -- nothing extra: the collector serves all six items from one cached fetch.
 ('Camera storage detail', 'camera.storage.info', 'CAMERA_STORAGE', 'TEXT', '',
  300, 7, 0, 10,
  'The card type, name, state and capacity exactly as the camera reports them. Kept because "why did it think that" is the question asked next.',
  '{"port":"{$CAMERA.HTTP.PORT}","path":"{$CAMERA.STORAGE.PATH}","username":"{$CAMERA.USER}","password":"{$CAMERA.PASSWORD}"}')
) AS v(name, key_, check_type, value_type, units, delay, hist, trend, tmo, descr, params)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

INSERT INTO trigger_def (tenant_id, host_id, description, expression, severity,
                         comments, manual_close, opdata)
SELECT 1, h.host_id, v.descr, v.expr, v.sev, v.comment, FALSE, v.opdata
FROM host h,
(VALUES
 -- HIGH, deliberately the same severity as a dead stream. The consequence is
 -- identical -- no recording -- and grading it lower would put it below the
 -- threshold most people alert on, which is how this fault stays invisible.
 ('Camera is not recording (storage failed)',
  'max(/template.camera/camera.storage.status,10m)=0 and max(/template.camera/camera.storage.count,10m)>0',
  'HIGH',
  'The camera has a card fitted but it is failed, unformatted or read-only, so nothing is being recorded. Live view will look perfectly normal. A read-only card is usually flash wear and needs replacing, not reformatting.',
  'Storage: {ITEM.LASTVALUE1}'),

 ('Camera has no storage card',
  'max(/template.camera/camera.storage.count,10m)=0',
  'HIGH',
  'The camera reports no storage device at all. Either no card is fitted, or one has failed hard enough to stop being detected -- the common end state of a worn or counterfeit SD card. Nothing is being recorded.',
  ''),

 -- Separate from "failed", and lower, because a full card on a camera set to
 -- overwrite is normal and expected. It only matters where overwrite is off.
 ('Camera storage is nearly full',
  'max(/template.camera/camera.storage.pfree,30m)<{$CAMERA.STORAGE.PFREE.MIN}',
  'WARNING',
  'Free space is below the configured floor. Harmless if the camera is set to overwrite the oldest footage, which is the usual configuration -- worth checking that it is.',
  'Free: {ITEM.LASTVALUE1}%')
) AS v(descr, expr, sev, comment, opdata)
WHERE h.host = 'template.camera' AND h.tenant_id = 1;

-- Suppressed while the camera is offline, like every other camera trigger.
-- A camera that has lost power cannot report its storage either, and without
-- this a power cut would raise "no storage card" against every camera on the
-- affected switch.
INSERT INTO trigger_dependency (trigger_id, depends_on_trigger_id)
SELECT dependent.trigger_id, master.trigger_id
FROM trigger_def dependent
JOIN trigger_def master
  ON master.host_id = dependent.host_id
 AND master.description = 'Camera is OFFLINE'
JOIN host h ON h.host_id = dependent.host_id
WHERE h.host = 'template.camera'
  AND dependent.description IN (
      'Camera is not recording (storage failed)',
      'Camera has no storage card',
      'Camera storage is nearly full');
