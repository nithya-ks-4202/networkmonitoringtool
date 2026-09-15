-- ---------------------------------------------------------------------------
-- Reclassifies credential macros that were stored as readable text.
--
-- MacroType.SECRET was honoured when a host was read -- masked as ****** --
-- but nothing ever set it, so every macro created through the API was TEXT.
-- The effect was that a camera's password came back in clear from
-- GET /api/hosts/{id} to anyone holding host.read, while the SNMP community
-- string on the same host was withheld. Classifying on creation fixes new
-- hosts; this fixes the ones already stored, which would otherwise keep
-- leaking for as long as they exist.
--
-- The pattern matches whole segments of the macro name, so {$API.KEY} is a
-- credential and {$MONKEY.COUNT} is not. It mirrors MacroType.defaultFor;
-- the two are tested against the same names.
--
-- Only TEXT rows are touched. A macro somebody has already marked SECRET or
-- VAULT keeps the decision that was made for it.
-- ---------------------------------------------------------------------------

UPDATE host_macro
   SET type = 'SECRET'
 WHERE type = 'TEXT'
   AND upper(macro) ~ '(^|[{$._])(PASSWORD|PASSPHRASE|SECRET|TOKEN|KEY|CREDENTIAL|COMMUNITY)([}._]|$)';

UPDATE global_macro
   SET type = 'SECRET'
 WHERE type = 'TEXT'
   AND upper(macro) ~ '(^|[{$._])(PASSWORD|PASSPHRASE|SECRET|TOKEN|KEY|CREDENTIAL|COMMUNITY)([}._]|$)';
