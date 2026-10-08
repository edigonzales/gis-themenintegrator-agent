-- In order to "reset" all permissions, we drop the DB schema roles completely
-- and recreate them afterwards.
-- This requires to first revoke all privileges granted to these roles on schema and tables.


-- Drop roles
REVOKE ALL PRIVILEGES
ON SCHEMA ${dbSchema}
FROM ${dbSchema}${roleSuffix}_read, ${dbSchema}${roleSuffix}_write;

REVOKE ALL PRIVILEGES
ON ALL TABLES IN SCHEMA ${dbSchema}
FROM ${dbSchema}${roleSuffix}_read, ${dbSchema}${roleSuffix}_write;

REVOKE ALL PRIVILEGES
ON ALL SEQUENCES IN SCHEMA ${dbSchema}
FROM ${dbSchema}${roleSuffix}_read, ${dbSchema}${roleSuffix}_write;

DROP ROLE ${dbSchema}${roleSuffix}_read, ${dbSchema}${roleSuffix}_write;




-- Create role with read privilege
CREATE ROLE ${dbSchema}${roleSuffix}_read;

GRANT USAGE ON SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_read;

GRANT SELECT
ON ALL TABLES IN SCHEMA ${dbSchema}
TO ${dbSchema}${roleSuffix}_read;

-- We currently don't implement SELECT privilege on sequences,
-- but it may be required in the future:
-- GRANT SELECT
-- ON ALL SEQUENCES IN SCHEMA ${dbSchema}
-- TO ${dbSchema}${roleSuffix}_read;


-- Create role with write privilege
CREATE ROLE ${dbSchema}${roleSuffix}_write;

GRANT USAGE ON SCHEMA ${dbSchema} TO ${dbSchema}${roleSuffix}_write;

GRANT SELECT, INSERT, UPDATE, DELETE
ON ALL TABLES IN SCHEMA ${dbSchema}
TO ${dbSchema}${roleSuffix}_write;

-- We currently don't implement SELECT privilege on sequences,
-- but it may be required in the future:
-- GRANT SELECT, USAGE
GRANT USAGE
ON ALL SEQUENCES IN SCHEMA ${dbSchema}
TO ${dbSchema}${roleSuffix}_write;
