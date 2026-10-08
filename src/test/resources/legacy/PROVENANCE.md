The shared Gradle/SQL fixture is copied unchanged from sogis/schema-jobs,
commit b68aaf4e48a8f776f7113115141063d39e2c3fd4, under the MIT license
in SCHEMA-JOBS-LICENSE. It is used only for synthetic integration tests.
Production execution always reads the chosen schema-jobs checkout.

Json, Problem and EofStdioTransport originated in the neighbouring
datenportal-themenintegrator-agent (MIT). The guarded PostgreSQL drop
approach and the serialized SDK-2.0.1 STDIO sender follow netl-mcp (MIT).
Their original copyright notices are
preserved in runtime/THIRD-PARTY-NOTICES.
