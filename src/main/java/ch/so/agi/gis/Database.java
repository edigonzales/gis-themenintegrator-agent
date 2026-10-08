package ch.so.agi.gis;

import java.sql.*;
import java.util.*;

final class Database {
  static Connection connect(DockerRuntime runtime, Map<String, Object> run, String db, String role)
      throws SQLException {
    try {
      Class.forName("org.postgresql.Driver");
    } catch (ClassNotFoundException e) {
      throw new SQLException("PostgreSQL JDBC driver missing", "08001", e);
    }
    return DriverManager.getConnection(
        runtime.jdbc(run, db, "gis_agent"), role, runtime.password(run));
  }

  static void bootstrap(DockerRuntime runtime, Map<String, Object> run) {
    for (String db : List.of("edit", "pub"))
      try (var connection = connect(runtime, run, db, "postgres");
          var sql = connection.createStatement()) {
        String password = literal(runtime.password(run));
        for (String role : List.of("ddluser", "dmluser", "gretl", "gis_reader")) {
          try (var query = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?")) {
            query.setString(1, role);
            try (var result = query.executeQuery()) {
              if (!result.next())
                sql.execute(
                    "CREATE ROLE "
                        + ident(role)
                        + " LOGIN PASSWORD "
                        + password
                        + (role.equals("ddluser") ? " CREATEROLE" : ""));
            }
          }
        }
        sql.execute("GRANT CREATE ON DATABASE " + ident(db) + " TO ddluser");
        sql.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
      } catch (SQLException e) {
        throw new Problem(
            "database_bootstrap_failed",
            "Lokale DB-Rollen können nicht eingerichtet werden.",
            "database",
            db,
            "sql_state",
            e.getSQLState(),
            "detail",
            runtime.p.redact(e.getMessage()),
            "cause",
            e.getCause() == null ? "" : runtime.p.redact(e.getCause().toString()));
      }
  }

  static String ident(String value) {
    if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))
      throw new Problem(
          "identifier_invalid", "Ungültiger PostgreSQL-Identifier.", "identifier", value);
    return "\"" + value + "\"";
  }

  static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  static List<Map<String, Object>> query(Connection c, String text, List<Object> values, int limit)
      throws SQLException {
    try (var statement = c.prepareStatement(text)) {
      statement.setQueryTimeout(15);
      statement.setMaxRows(limit);
      for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
      var rows = new ArrayList<Map<String, Object>>();
      try (var result = statement.executeQuery()) {
        while (result.next()) {
          var row = Json.map();
          for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
            Object value = result.getObject(i);
            row.put(
                result.getMetaData().getColumnLabel(i),
                value == null || value instanceof Number || value instanceof Boolean
                    ? value
                    : value.toString());
          }
          rows.add(row);
        }
      }
      return rows;
    }
  }

  static boolean exists(Connection c, String schema) throws SQLException {
    return !query(c, "SELECT 1 FROM pg_namespace WHERE nspname=?", List.of(schema), 1).isEmpty();
  }

  static Map<String, Object> snapshot(Connection c, String schema) throws SQLException {
    var result = Json.map("exists", exists(c, schema));
    if (!Boolean.TRUE.equals(result.get("exists"))) return result;
    c.setAutoCommit(false);
    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    try {
      result.put(
          "columns",
          query(
              c,
              "SELECT table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default FROM information_schema.columns WHERE table_schema=? ORDER BY table_name,ordinal_position",
              List.of(schema),
              10000));
      result.put(
          "constraints",
          query(
              c,
              "SELECT r.relname AS table_name,con.conname,con.contype::text,pg_get_constraintdef(con.oid) AS definition FROM pg_constraint con JOIN pg_class r ON r.oid=con.conrelid JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname=? ORDER BY r.relname,con.conname",
              List.of(schema),
              10000));
      result.put(
          "indexes",
          query(
              c,
              "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname=? ORDER BY tablename,indexname",
              List.of(schema),
              10000));
      result.put(
          "geometries",
          query(
              c,
              "SELECT f_table_name,f_geometry_column,srid,type FROM geometry_columns WHERE f_table_schema=? ORDER BY f_table_name,f_geometry_column",
              List.of(schema),
              10000));
      result.put(
          "views",
          query(
              c,
              "SELECT viewname,definition FROM pg_views WHERE schemaname=? ORDER BY viewname",
              List.of(schema),
              10000));
      result.put(
          "permissions",
          query(
              c,
              "SELECT c.relname,c.relkind::text,c.relowner::bigint,c.relacl::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? ORDER BY c.relname",
              List.of(schema),
              10000));
      result.put(
          "schema",
          query(
              c,
              "SELECT nspowner::bigint,nspacl::text FROM pg_namespace WHERE nspname=?",
              List.of(schema),
              1));
      result.put(
          "models",
          query(
              c,
              "SELECT filename,modelname,content FROM "
                  + ident(schema)
                  + ".t_ili2db_model ORDER BY filename,modelname",
              List.of(),
              10000));
      result.put(
          "attribute_mapping",
          query(
              c,
              "SELECT to_jsonb(m)::text AS mapping FROM "
                  + ident(schema)
                  + ".t_ili2db_attrname m ORDER BY iliname,sqlname",
              List.of(),
              10000));
      result.put(
          "class_mapping",
          query(
              c,
              "SELECT to_jsonb(m)::text AS mapping FROM "
                  + ident(schema)
                  + ".t_ili2db_classname m ORDER BY iliname,sqlname",
              List.of(),
              10000));
      result.put(
          "roles",
          query(
              c,
              "SELECT oid::bigint,rolname,rolsuper,rolcreaterole,rolcanlogin FROM pg_roles WHERE rolname LIKE ? ORDER BY rolname",
              List.of(schema + "%"),
              10000));
      result.put(
          "memberships",
          query(
              c,
              "SELECT r.rolname AS role,m.rolname AS member FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.roleid JOIN pg_roles m ON m.oid=a.member WHERE r.rolname LIKE ? ORDER BY r.rolname,m.rolname",
              List.of(schema + "%"),
              10000));
      c.commit();
      return result;
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  static Map<String, Object> inspect(
      DockerRuntime runtime, Map<String, Object> run, String db, String schema) {
    try (var c = connect(runtime, run, db, "postgres")) {
      return snapshot(c, schema);
    } catch (SQLException e) {
      throw new Problem(
          "inspection_failed",
          "Schema nicht prüfbar.",
          "sql_state",
          e.getSQLState(),
          "database",
          db);
    }
  }

  static void localRoles(
      DockerRuntime runtime, Map<String, Object> run, String db, Collection<String> recipients) {
    try (var c = connect(runtime, run, db, "postgres");
        var st = c.createStatement()) {
      for (String role : recipients) {
        ident(role);
        if (query(c, "SELECT 1 FROM pg_roles WHERE rolname=?", List.of(role), 1).isEmpty())
          st.execute("CREATE ROLE " + ident(role) + " NOLOGIN");
      }
    } catch (SQLException e) {
      throw new Problem(
          "roles_failed", "Lokale Empfängerrollen nicht anlegbar.", "sql_state", e.getSQLState());
    }
  }

  static void localDml(DockerRuntime runtime, Map<String, Object> run, String db, String role) {
    try (var c = connect(runtime, run, db, "postgres");
        var st = c.createStatement()) {
      st.execute("GRANT " + ident(role) + " TO dmluser");
      st.execute("GRANT " + ident(role.replaceFirst("_write$", "_read")) + " TO gis_reader");
    } catch (SQLException e) {
      throw new Problem(
          "grant_failed",
          "Lokale DML-Mitgliedschaft fehlgeschlagen.",
          "sql_state",
          e.getSQLState());
    }
  }

  static int dml(DockerRuntime runtime, Map<String, Object> run, String db, String sql) {
    try (var c = connect(runtime, run, db, "dmluser");
        var st = c.createStatement()) {
      c.setAutoCommit(false);
      st.setQueryTimeout(runtime.s.timeout);
      try {
        st.execute(sql);
        int count = st.getUpdateCount();
        c.commit();
        return count;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new Problem(
          "data_failed",
          "SQL-Datenänderung zurückgerollt.",
          "sql_state",
          e.getSQLState(),
          "message_detail",
          runtime.p.redact(e.getMessage()));
    }
  }

  static List<Map<String, Object>> read(
      DockerRuntime runtime, Map<String, Object> run, String db, String sql, int limit) {
    try (var c = connect(runtime, run, db, "gis_reader")) {
      c.setReadOnly(true);
      c.setAutoCommit(false);
      try {
        return query(c, sql, List.of(), Math.clamp(limit, 1, 1000));
      } finally {
        c.rollback();
      }
    } catch (SQLException e) {
      throw new Problem(
          "query_failed",
          "Leseabfrage fehlgeschlagen.",
          "sql_state",
          e.getSQLState(),
          "message_detail",
          runtime.p.redact(e.getMessage()));
    }
  }

  static void terminate(DockerRuntime runtime, Map<String, Object> run) {
    for (String db : List.of("edit", "pub"))
      try (var c = connect(runtime, run, db, "postgres")) {
        query(
            c,
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name LIKE ? AND pid<>pg_backend_pid()",
            List.of("gis_" + Json.required(run, "id").substring(0, 8) + "_%"),
            1000);
      } catch (Exception e) {
        run.put("recovery", Json.map("reason", "database_termination_unconfirmed"));
      }
  }

  static void guardedDrop(
      DockerRuntime runtime,
      Map<String, Object> run,
      String db,
      String schema,
      List<String> roles,
      boolean commit) {
    ident(schema);
    String guard = "gis_guard_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = connect(runtime, run, db, "postgres");
        var st = c.createStatement()) {
      c.setAutoCommit(false);
      try {
        st.execute("SET LOCAL lock_timeout='5s'");
        st.execute(
            "CREATE FUNCTION public."
                + guard
                + "() RETURNS event_trigger LANGUAGE plpgsql AS $guard$ BEGIN IF EXISTS (SELECT 1 FROM pg_event_trigger_dropped_objects() WHERE schema_name IS DISTINCT FROM "
                + literal(schema)
                + " AND NOT COALESCE((schema_name='pg_toast' AND NOT original AND NOT normal AND object_type IN ('toast table','index')),false) AND NOT (object_type='schema' AND object_name="
                + literal(schema)
                + ")) THEN RAISE EXCEPTION 'GIS_EXTERNAL_DEPENDENCY'; END IF; END $guard$");
        st.execute(
            "CREATE EVENT TRIGGER "
                + guard
                + " ON sql_drop EXECUTE FUNCTION public."
                + guard
                + "()");
        st.execute("DROP SCHEMA " + ident(schema) + " CASCADE");
        st.execute("DROP EVENT TRIGGER " + guard);
        st.execute("DROP FUNCTION public." + guard + "()");
        for (String role : roles) st.execute("DROP ROLE " + ident(role));
        if (commit) c.commit();
        else c.rollback();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new Problem(
          "drop_blocked",
          "Schema-Löschung blockiert oder externe Abhängigkeiten vorhanden.",
          "sql_state",
          e.getSQLState(),
          "message_detail",
          e.getMessage());
    }
  }
}
