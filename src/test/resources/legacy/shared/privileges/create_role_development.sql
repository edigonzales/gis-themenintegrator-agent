-- Dies ist eine Variante, damit auch in einer Entwicklungs-DB die späteren GRANT-Skripte funktionieren.
-- Andere Variante ist, dass man die "Globals" vorgängig importiert (und dabei Fehler ignoriert, weil z.B. die Rolle gretl bereits existiert).


-- Application users
-- DROP ROLE IF EXISTS gretl; -- don't drop/create because it's already included in the development DBs
DROP ROLE IF EXISTS oereb_service;
DROP ROLE IF EXISTS ogc_server;
DROP ROLE IF EXISTS report_server;
DROP ROLE IF EXISTS sogis_service;
DROP ROLE IF EXISTS sogis_service_write;

-- CREATE ROLE gretl; -- don't drop/create because it's already included in the development DBs
CREATE ROLE oereb_service;
CREATE ROLE ogc_server;
CREATE ROLE report_server;
CREATE ROLE sogis_service;
CREATE ROLE sogis_service_write;



-- DB users and groups
DROP ROLE IF EXISTS ldap_roles;
DROP ROLE IF EXISTS barpa;
DROP ROLE IF EXISTS bdafu;
DROP ROLE IF EXISTS bdhba;
DROP ROLE IF EXISTS bdsek;
DROP ROLE IF EXISTS bjsvw;
DROP ROLE IF EXISTS bvtaa;
DROP ROLE IF EXISTS bwwaa;
DROP ROLE IF EXISTS edarc;
DROP ROLE IF EXISTS edden;
DROP ROLE IF EXISTS fdaio;
DROP ROLE IF EXISTS fdstv;
DROP ROLE IF EXISTS ffivw;
DROP ROLE IF EXISTS sokapo;
DROP ROLE IF EXISTS vdgem;
DROP ROLE IF EXISTS vkfaa;
DROP ROLE IF EXISTS vlbzw;
DROP ROLE IF EXISTS vlwaa;
DROP ROLE IF EXISTS vmzaa;
-- Falls wir diese noch brauchen:
-- DROP ROLE IF EXISTS av_write;
-- DROP ROLE IF EXISTS gdi_write;


CREATE ROLE ldap_roles;
CREATE ROLE barpa;
CREATE ROLE bdafu;
CREATE ROLE bdhba;
CREATE ROLE bdsek;
CREATE ROLE bjsvw;
CREATE ROLE bvtaa;
CREATE ROLE bwwaa;
CREATE ROLE edarc;
CREATE ROLE edden;
CREATE ROLE fdaio;
CREATE ROLE fdstv;
CREATE ROLE ffivw;
CREATE ROLE sokapo;
CREATE ROLE vdgem;
CREATE ROLE vkfaa;
CREATE ROLE vlbzw;
CREATE ROLE vlwaa;
CREATE ROLE vmzaa;
-- Falls wir diese noch brauchen:
-- CREATE ROLE av_write;
-- CREATE ROLE gdi_write;


-- DB users...
