-- Drop roles first in case they still exist for some reason
DROP ROLE IF EXISTS ${dbSchema}${roleSuffix}_read;
DROP ROLE IF EXISTS ${dbSchema}${roleSuffix}_write;

-- Create roles
CREATE ROLE ${dbSchema}${roleSuffix}_read;
CREATE ROLE ${dbSchema}${roleSuffix}_write;
