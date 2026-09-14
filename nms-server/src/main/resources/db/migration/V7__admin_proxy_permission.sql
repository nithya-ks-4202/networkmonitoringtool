-- ---------------------------------------------------------------------------
-- Let administrators manage proxies.
--
-- The Admin role could read proxies but not create one, so obtaining an
-- enrolment token required a super admin. On a deployment where proxies are
-- how remote sites are monitored at all, that made adding a site an
-- escalation rather than an ordinary administrative task.
--
-- Applied as its own migration rather than by editing the seed: changing an
-- already-applied migration breaks its checksum on every installed instance.
-- ---------------------------------------------------------------------------

UPDATE role
SET permissions = permissions || '["proxy.write"]'::jsonb
WHERE role_type = 'ADMIN'
  AND builtin = TRUE
  -- Idempotent: re-running must not add the entry twice, and an operator may
  -- have granted it by hand already.
  AND NOT permissions @> '["proxy.write"]'::jsonb;
