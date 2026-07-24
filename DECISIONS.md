# Design Decisions & Optimization Roadmap

Walking through the architectural choices that shaped **data-flow-filter**, the trade-offs we weighed, alternatives we rejected, and the roadmap for turning this into a fully optimized production pipeline.

---

## Architecture Decisions

### 1. No web framework — `com.sun.net.httpserver` (JDK built-in)

**Why:** The service exposes 4 endpoints (`POST /import`, `POST /query`, `GET /health`, `GET /info`). Adding Spring Boot or Javalin pulls in hundreds of classes, increases startup time from ~1s to ~5s, and adds a meaningful JAR size increase. The JDK's built-in HTTP server handles everything we need: request routing, multipart parsing (done manually), and async execution via a thread pool.

**Trade-off:** No built-in request body streaming, no automatic JSON serialization, no servlet filters for middleware (CORS is handled manually). For a service this small, that's acceptable.

**Alternative considered:** Javalin (single JAR, ~2MB). Rejected — adds a dependency we don't need for 4 endpoints.

### 2. EAV (Entity-Attribute-Value) schema in SQLite

**Why:** John Deere CSVs have 18 columns for tractors and 42 columns for combines — and those column sets differ by machine model and firmware version. A traditional relational schema would require `ALTER TABLE` every time a new metric appears. EAV stores each metric value as a row in `telemetry_metrics(event_id, metric_name, num_value, str_value)`, so new metrics are zero-schema-change.

**Trade-off:** Queries are more complex (two-phase: find event IDs, then fetch full data) and slower than wide-table scans. We accept this because query volume is low compared to import volume, and the flexibility is essential.

**Alternative considered:** SQLite's `WITHOUT ROWID` tables or a JSON blob column. Rejected — JSON column prevents indexed filtering on individual metrics; EAV gives us both flexibility and indexability.

### 3. SQLite with WAL journal mode

**Why:** Zero operational overhead — no database server to install, configure, or monitor. WAL mode was chosen over the default rollback journal because it allows concurrent readers and writers (only one writer at a time, but readers don't block). For a telemetry service where imports are batched and queries are ad-hoc, this is the sweet spot.

**Trade-off:** Single-writer limitation means concurrent imports serialize. Acceptable because CSV imports are operator-initiated, not firehose ingestion.

**Alternative considered:** PostgreSQL. Rejected for v1 — adds infrastructure complexity. The migration path is straightforward (same SQL dialect, Flyway handles migrations) if we outgrow SQLite.

### 4. Flyway for schema migrations

**Why:** Version-controlled schema changes that auto-apply on startup. Even with one migration so far (`V1__init_schema.sql`), this establishes the pattern for future schema evolution without manual `sqlite3` commands.

**Alternative considered:** Liquibase (XML/YAML migrations). Rejected — Flyway's SQL-first approach is simpler for a Java-only project.

### 5. Header normalization (`"GPS longitude [°]"` -> `"GpsLongitude"`)

**Why:** John Deere headers contain units, special characters, and inconsistent casing. Normalizing to PascalCase metric names creates a stable, queryable identifier that is independent of the source CSV format. The original header is preserved in `metric_definitions.description` for traceability.

**Trade-off:** Information loss in the name (the unit is stripped). Mitigated by storing the unit separately in `metric_definitions.unit`.

### 6. Type inference from header patterns and sample values

**Why:** The CSV is untyped — every value is a string. We infer `NUMBER`, `BOOLEAN`, or `STRING` by checking: (1) header suffix `[I/O]` -> BOOLEAN, (2) first 3 non-empty sample values parse as doubles -> NUMBER, (3) fallback -> STRING. This happens once at import time and is recorded in `metric_definitions`.

**Trade-off:** Inference can be wrong if the first 20 samples are unrepresentative. The `metric_definitions` table acts as the source of truth — once registered, the type sticks. Manual correction requires a direct DB edit.

**Alternative considered:** Schema-on-read (infer type at query time). Rejected — inconsistent and slow. Schema-on-write (infer at import) is faster at query time and gives us validation.

### 7. Batch commits every 500 rows during import

**Why:** SQLite auto-commit mode (one transaction per statement) is extremely slow for bulk inserts. Batching 500 rows per transaction gives a ~10-50x speedup. The 500-row interval balances throughput against rollback cost (if a row fails mid-batch, at most 499 good rows are rolled back — they get re-imported on retry).

**Trade-off:** Partial rollback on error. Mitigated by per-row try-catch: failed rows are skipped and counted, not retried.

### 8. Two-phase query execution

**Why:** EAV queries can't be a single `SELECT * FROM telemetry_events WHERE ...` because metric filters live in `telemetry_metrics`. Phase 1 finds matching event IDs using subqueries per metric. Phase 2 fetches full event data + all metrics for those IDs.

**Trade-off:** Phase 2 loads ALL metrics for matched events, even if the query only filters on one. This is a deliberate choice — the API consumer wants the full event picture, not just the filtered fields.

### 9. Lombok for boilerplate reduction

**Why:** `@NonNull` generates null checks, records eliminate getter/setter boilerplate, `@Slf4j` adds logger fields. With Java 25 records handling most data classes, Lombok's role is minimal — mainly `@NonNull` for defensive programming and `@Slf4j` for logging.

**Alternative considered:** Plain Java getters/setters. Rejected — verbose for data classes. Records cover 90% of the need; Lombok fills the rest.

### 10. Semicolon-delimited CSV parsing with univocity-parsers

**Why:** John Deere exports use semicolons (European CSV convention). Univocity is the fastest Java CSV parser, handles BOM stripping, and supports custom delimiters without regex overhead.

**Alternative considered:** OpenCSV. Rejected — slower and requires more configuration for the same result.

---

## Optimization Roadmap

### Priority 1: Pagination on `/query` endpoint

**Problem:** The current `/query` returns ALL matching events in a single response. With 9,028 events in test data and potential millions in production, this means:
- Massive JSON payloads (memory pressure on server and client)
- Long response times for broad queries
- No way for the client to progressively load results

**Approach:**
- Add `page` (default 0) and `pageSize` (default 100, max 1000) query parameters or request body fields
- Phase 1 (find event IDs) remains the same, but apply `LIMIT` and `OFFSET` (or cursor-based pagination using `WHERE id > last_seen_id ORDER BY id LIMIT ?`)
- Return `{ results: [...], totalCount: N, page: N, pageSize: M, hasMore: boolean }`
- Cursor-based pagination is preferred over offset-based for large datasets because `OFFSET` scans and discards N rows each time

**Trade-off:** Cursor pagination requires the client to track the last event ID. Offset pagination is simpler but degrades with large offsets. Recommend cursor-based with an offset fallback for small datasets.

### Priority 2: Cache `metric_definitions` in memory

**Problem:** Every query that filters on EAV metrics hits the database to look up `metric_definitions` for type validation. The `metric_definitions` table is small (~42 rows) and changes only during import.

**Approach:**
- Load all metric definitions into a `ConcurrentHashMap<String, MetricDefinition>` at startup
- Invalidate (reload) the cache after each successful CSV import
- The import handler signals the cache to refresh
- Eliminates N database round-trips per query (one per unique filtered metric)

**Cost:** Negligible memory (~42 records = few KB). High impact on query latency.

### Priority 3: Observability — Logging, Metrics, Tracing

**Problem:** Current logging is minimal (Logback to console, INFO level). No request timing, no error rates, no query performance visibility.

**Approach:**
- **Structured logging:** Switch log pattern to include request ID, endpoint, and duration. Use MDC (Mapped Diagnostic Context) for request correlation.
- **Micrometer metrics:** Embed Micrometer (no vendor lock-in) and expose a `GET /metrics` endpoint in Prometheus format:
  - `http_server_requests_duration_seconds` (histogram by endpoint, status)
  - `import_rows_total` (counter)
  - `import_errors_total` (counter)
  - `query_results_total` (counter)
  - `db_query_duration_seconds` (histogram — how long Phase 1 and Phase 2 take)
- **OpenTelemetry tracing:** Add OTel SDK with Jaeger/Otlp exporter. Trace each request through HTTP handler -> service -> DB, capturing SQL queries as spans.
- **Grafana dashboards:** Pre-built dashboards for:
  - Request rate and latency (P50, P95, P99)
  - Import throughput (rows/sec)
  - Database size growth over time
  - Error rate by endpoint

**Why Grafana over custom UI:** Standard tooling, no frontend work, alerting built-in.

### Priority 4: Performance testing for CSV imports

**Problem:** We don't know how the import pipeline behaves under stress — large files (100K+ rows), concurrent imports, memory pressure.

**Approach:**
- **k6 load test script** (`tests/k6/import.js`):
  - Generate synthetic CSVs of varying sizes (1K, 10K, 50K, 100K, 500K rows)
  - Measure import throughput (rows/sec) as a function of file size
  - Test concurrent imports (2, 4, 8 parallel POSTs) — SQLite serializes writes, so expect queuing
  - Monitor memory usage (heap + native) during import — univocity loads all rows into memory via `parseAll()`
- **Key finding anticipated:** `parser.parseAll(new StringReader(csvContent))` loads the entire CSV into a `List<String[]>` before processing. For large files, switch to streaming parsing (`CsvParser.iterate()`) to avoid holding all rows in memory.

**Success criteria:** Import 100K rows in under 30 seconds with <500MB heap.

### Priority 5: Performance testing for queries under load

**Problem:** We don't know query latency under concurrent load or where the breaking point is.

**Approach:**
- **k6 load test script** (`tests/k6/query.js`):
  - Baseline: Single-threaded query latency for each filter type (core field, EAV metric, combined)
  - Scale-up: Increase concurrent threads (1, 2, 4, 8, 16, 32, 64) and measure:
    - P50/P95/P99 latency
    - Error rate (timeouts, connection failures)
    - Thread pool exhaustion (current pool size: 4)
  - Dataset scale: Test against 10K, 100K, 1M, 5M events
  - Identify the crash point — when does the thread pool saturate? When does SQLite lock contention become a problem?

**Key finding anticipated:** The fixed thread pool of 4 is the bottleneck. Under load, requests queue. Recommendation: increase pool size or switch to a virtual thread executor (Java 25+).

### Priority 6: Database index optimization (after performance testing)

**Problem:** Current indexes are generic. Performance testing will reveal actual query patterns, guiding targeted index additions.

**Current indexes:**
```sql
idx_metrics_name         ON telemetry_metrics(metric_name)
idx_metrics_num          ON telemetry_metrics(metric_name, num_value)
idx_metrics_str          ON telemetry_metrics(metric_name, str_value)
idx_events_recorded_at   ON telemetry_events(recorded_at)
idx_events_machine       ON telemetry_events(machine_id)
```

**Indexes to add after profiling:**
```sql
-- Composite index for the most common query pattern: filter by machine + time range
CREATE INDEX idx_events_machine_time ON telemetry_events(machine_id, recorded_at);

-- Covering index for EAV subqueries (avoids table lookup for event_id)
CREATE INDEX idx_metrics_name_num_eventid ON telemetry_metrics(metric_name, num_value, event_id);
CREATE INDEX idx_metrics_name_str_eventid ON telemetry_metrics(metric_name, str_value, event_id);

-- Index for pagination (cursor-based on id)
-- Already covered by PRIMARY KEY on telemetry_events(id)

-- Index for date range queries (most common filter)
-- Already covered by idx_events_recorded_at, but consider:
CREATE INDEX idx_events_time_machine ON telemetry_events(recorded_at, machine_id);
```

**Trade-off:** Each index slows writes (import) by ~5-10% but speeds reads. Only add indexes that profiling shows are used. SQLite's `EXPLAIN QUERY PLAN` is the tool for this.

### Priority 7: Java 25 virtual threads for the HTTP server

**Problem:** The current `FixedThreadPool(4)` limits concurrency. Increasing the pool size helps but bounded threads waste resources when idle.

**Approach:** Replace `Executors.newFixedThreadPool(config.serverThreadPoolSize())` with `Executors.newVirtualThreadPerTaskExecutor()` (Java 21+). Virtual threads are lightweight (KB vs MB), scheduled by the JVM, and block I/O without holding OS threads.

**Impact:** Handle thousands of concurrent requests without tuning thread counts. SQLite's single-writer limitation remains the bottleneck for imports, but read queries (the common case) scale freely.

**Cost:** Zero — Java 25 has virtual threads production-ready. One-line change.

### Priority 8: Streaming CSV parsing (memory optimization)

**Problem:** `parser.parseAll(new StringReader(csvContent))` materializes all rows in memory. A 500K-row CSV with 42 columns = ~500K `String[]` arrays = significant heap pressure.

**Approach:** Switch to univocity's streaming API:
```java
var parser = new CsvParser(settings);
var rows = parser.iterate(new StringReader(csvContent));
// Skip header
var headers = rows.next();
// Process row by row
while (rows.hasNext()) {
    var row = rows.next();
    importRow(conn, row, ...);
}
```

**Impact:** Memory usage drops from O(rows x columns) to O(columns) — constant regardless of file size.

### Priority 9: Connection pooling for queries

**Problem:** `getConnection()` creates a new JDBC connection per query. SQLite connections are cheap but not free — each one runs PRAGMA setup (WAL mode, foreign keys).

**Approach:** For read-heavy workloads, maintain a single long-lived connection for queries (SQLite WAL allows concurrent readers). Use a separate connection for writes (imports). This eliminates per-query PRAGMA overhead.

**Alternative:** HikariCP connection pool. Overkill for SQLite — a single shared read connection is simpler and sufficient.

### Priority 10: API improvements

**Approach:**
- **`GET /metrics-list`** — list all registered metric definitions (name, type, unit) so clients can build dynamic filter UIs without guessing field names
- **`GET /machines`** — list all machines with event counts
- **`POST /import` with progress** — for large imports, return progress via SSE (Server-Sent Events) or a polling endpoint (`GET /import/{id}/status`)
- **Rate limiting** — protect against accidental firehose imports (e.g., max 1 import at a time, reject concurrent imports with 429)

### Priority 11: Data retention and partitioning

**Problem:** The `telemetry_events` and `telemetry_metrics` tables grow unbounded. With 9K events/day per machine and 100 machines, that's ~33M events/year.

**Approach:**
- **Time-based archival:** Add an `archive` endpoint that moves events older than N days to a separate SQLite file (compressed)
- **Purge old data:** `DELETE FROM telemetry_metrics WHERE event_id IN (SELECT id FROM telemetry_events WHERE recorded_at < ?)` — run periodically
- **Partitioning (if migrating to PostgreSQL):** Table partition by month for `telemetry_events` and `telemetry_metrics`

### Priority 12: Security hardening (before production)

**Current gaps:**
- No authentication — anyone can import or query
- CORS allows all origins (`*`)
- No input size limits — a malicious 10GB CSV body gets read into memory
- No HTTPS — runs on plain HTTP

**Approach:**
- Add API key authentication (header `X-API-Key`) — simple, no OAuth overhead
- Configure CORS with specific origins
- Add `maxRequestSize` configuration to reject oversized bodies
- Document TLS termination via reverse proxy (nginx, Caddy) as the recommended deployment pattern

### Priority 13: Unit test coverage

**Problem:** The project has zero unit tests — JUnit Jupiter is declared as a dependency but `src/test/` is empty. All verification is done through `test.sh`, a ~950-line Bash integration test. Integration tests catch end-to-end bugs but are slow (spin up the full server, import CSVs, tear down), don't isolate individual components, and can't easily cover edge cases inside business logic.

**What to unit test (high-impact targets):**
- **`CSVImportService.normalizeHeader()`** — static method, pure function. Test every transformation: unit stripping (`[°]`, `[km/h]`, `[I/O]`), special character replacement (`/`, `°`, `.`), multi-space collapse, PascalCase conversion, empty/whitespace input. This is the single most exercised code path — runs once per column per import.
- **`CSVImportService.inferDataType()`** — test BOOLEAN detection (`[I/O]` header, `on`/`off`/`active`/`inactive` values), NUMBER detection (3+ numeric samples), STRING fallback, all-NA columns, mixed valid/invalid samples.
- **`CSVImportService.extractUnit()`** — test bracket extraction, `[I/O]` skip, nested brackets, no brackets.
- **`CSVImportService.parseBoolean()`** — test every variant: `true`/`false`, `on`/`off`, `active`/`inactive`, `i`/`o`, `1`/`0`, case insensitivity.
- **`SQLiteDB.findMatchingEventIds()`** — test SQL generation for core field filters, EAV metric subqueries, combined filters, empty filter list, multiple filters on same metric.
- **`SQLiteDB.getOperatorSql()`** — test every operation: `Equals`, `LessThan`, `GreaterThan`, `Contains`, unknown operation.
- **`SQLiteDB.prepareFilterValue()`** — test boolean conversion (`true` -> `1.0`, `"on"` -> `1.0`, `"0"` -> `0.0`), LIKE wildcard wrapping, passthrough for numbers and strings.
- **`SQLiteDB.getStorageColumn()`** — test `NUMBER` -> `num_value`, `BOOLEAN` -> `num_value`, `STRING` -> `str_value`, unknown type throws.
- **`QueryHandler.validateFilters()`** — test field existence validation, operation compatibility per data type, value type checking, null/empty field rejection.
- **`CoreField.isCoreField()` / `getCoreField()` / `coreFieldToColumn()`** — test case insensitivity, all 6 core fields, unknown field behavior.
- **`Config.parse()`** — test valid config, missing keys (fallback defaults), invalid port range, invalid thread pool size, system property override.

**Approach:**
- JUnit Jupiter (already a dependency) with AssertJ for fluent assertions
- Use an in-memory SQLite database (`jdbc:sqlite::memory:`) for DB-layer unit tests — no filesystem dependency
- Test class per source class: `CSVImportServiceTest`, `SQLiteDBTest`, `QueryHandlerTest`, `ConfigTest`, `CoreFieldTest`
- Target: 80%+ branch coverage on business logic classes (import service, query handler, DB layer)

**Why not just rely on integration tests:** Integration tests verify the pipeline works but don't tell you *where* it broke. Unit tests isolate the faulty component, run in milliseconds (not seconds), and serve as executable documentation for edge-case behavior.

### Priority 14: Proper health check endpoints (Kubernetes-style probes)

**Problem:** The current `/health` endpoint returns machine/event counts — a useful statistic but not a health probe. It doesn't distinguish between "the JVM started" and "the database is accessible." Load balancers and orchestrators (Kubernetes, Docker Compose, systemd) need structured health signals to make lifecycle decisions.

**Approach — four distinct signals:**

| Endpoint | Purpose | What it checks | Response |
|----------|---------|----------------|----------|
| `GET /live` | **Liveness probe** | JVM is alive, HTTP server is accepting requests | `{"status":"alive"}` (200) — always succeeds if reachable |
| `GET /started` | **Startup probe** | Application finished initialization (Flyway migrations completed, HTTP server bound) | `{"status":"started"}` (200) or `{"status":"starting"}` (503) |
| `GET /ready` | **Readiness probe** | Application is ready to serve requests — database connection works, thread pool is available | `{"status":"ready"}` (200) or `{"status":"not_ready","reason":"..."}` (503) |
| `GET /health` | **Composite health** | Aggregates all checks with component-level detail | `{"status":"healthy","components":[...]}` (200) or `{"status":"unhealthy",...}` (503) |

**Implementation details:**
- **`/live`** — Minimal handler, no DB access. Returns 200 unconditionally. This is the signal Kubernetes uses to decide whether to kill and restart the pod.
- **`/started`** — A `volatile boolean started` flag in `Startup`, set to `true` after `migrateDatabase()` completes and `server.start()` succeeds. Returns 503 during initialization so load balancers don't send traffic to a booting instance.
- **`/ready`** — Opens a SQLite connection, runs `PRAGMA integrity_check`, verifies the thread pool isn't saturated. Returns 503 with a reason string if any check fails. This is the signal Kubernetes uses to decide whether to route traffic to the pod.
- **`/health`** — Composite endpoint that runs all the above checks plus additional diagnostics:
  - Database: connection test, WAL mode status, foreign key enforcement status, event/machine counts
  - Server: thread pool size, active threads, port
  - JVM: uptime, heap usage, GC count
  - Each component reports `{ name, status, details }` — `status` is `healthy`, `degraded`, or `unhealthy`

**Why four endpoints instead of one:** Orchestrators need different signals at different lifecycle stages. A liveness probe must be fast and reliable (never false-positive "dead"). A readiness probe can be thorough (DB check, thread pool check). Combining them into one endpoint forces trade-offs that break one use case or the other.

### Priority 15: Rich `/info` endpoint (application state without secrets)

**Problem:** The current `/info` endpoint returns the raw `Config` record — 6 properties (port, thread pool size, DB path, CORS settings). Useful but incomplete: it tells you how the app was configured, not how it's running.

**Approach — structured response with categorized sections:**

```json
{
  "application": {
    "name": "data-flow-filter",
    "version": "1.0.0-SNAPSHOT",
    "javaVersion": "25",
    "startTime": "2026-07-24T10:30:00Z",
    "uptimeSeconds": 3600
  },
  "configuration": {
    "serverPort": 8080,
    "serverThreadPoolSize": 4,
    "databasePath": "DB/data_flow-filter.db",
    "corsAllowOrigins": "*",
    "corsAllowMethods": "GET, POST, OPTIONS",
    "corsAllowHeaders": "Content-Type",
    "configSources": ["classpath:application.properties", "config/application.properties"]
  },
  "database": {
    "type": "sqlite",
    "version": "3.53.2.0",
    "walMode": true,
    "foreignKeysEnabled": true,
    "schemaVersion": "1",
    "path": "/absolute/path/to/data_flow-filter.db",
    "fileSizeBytes": 12345678
  },
  "statistics": {
    "machines": 4,
    "events": 9028,
    "metricDefinitions": 42,
    "metricRows": 379176,
    "lastImportTime": "2026-07-24T10:00:00Z"
  },
  "jvm": {
    "heapUsedBytes": 67108864,
    "heapMaxBytes": 536870912,
    "nonHeapUsedBytes": 33554432,
    "threadCount": 42,
    "gcCount": 15,
    "gcTimeMs": 230
  }
}
```

**Design principles:**
- **No secrets:** Never expose API keys, database passwords, or internal IPs. The current config has no secrets, but the pattern should be explicit.
- **Config sources:** List which config files were actually loaded (classpath defaults, user override, system properties) so operators can verify their overrides took effect.
- **Database state:** WAL mode and foreign key status are per-connection settings — reporting them confirms they're enforced. Schema version from Flyway tells you which migration is active.
- **JVM state:** Heap usage and thread count help diagnose memory leaks and thread exhaustion without needing JMX access.
- **Statistics:** Machine/event/metric counts give a quick data inventory. `lastImportTime` tells you how fresh the data is.

**Alternative considered:** Expose JMX metrics via Jolokia. Rejected — adds a dependency and opens a broad attack surface. The `/info` endpoint gives us the specific data points we need.

### Priority 16: `/version` endpoint (build metadata)

**Problem:** When running multiple instances or rolling deployments, there's no way to verify which version of the code is running on a given instance. The `version` in `build.gradle.kts` (`1.0.0-SNAPSHOT`) is generic — it doesn't tell you which commit is deployed.

**Approach — embed build-time metadata:**

```json
{
  "application": {
    "name": "data-flow-filter",
    "version": "1.0.0-SNAPSHOT"
  },
  "build": {
    "gitCommit": "bdf7a4b",
    "gitCommitFull": "bdf7a4b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7",
    "gitBranch": "main",
    "buildTime": "2026-07-24T12:00:00Z",
    "javaVersion": "25",
    "osName": "Linux",
    "osVersion": "7.0.0-28-generic"
  }
}
```

**Implementation:**
- Use Gradle's `processResources` task to generate a `build-info.properties` file at build time
- Read git data via JGit (add `org.eclipse.jgit:org.eclipse.jgit` as a buildscript dependency) or via `git rev-parse HEAD` in the Gradle task
- Write properties: `git.commit.id`, `git.commit.id.abbrev`, `git.branch`, `build.time`, `java.version`, `os.name`
- At runtime, load `build-info.properties` from the classpath and serve via `GET /version`
- If the properties file is missing (local dev build), return a fallback with `"build": {"note": "build metadata not available"}`

**Why not just use the Gradle version property:** The version string (`1.0.0-SNAPSHOT`) is manually set and doesn't change between builds. Git commit SHA is unique per build and lets you trace any runtime issue back to the exact code. This is essential for debugging in production ("which version introduced this regression?").

**Alternative considered:** Spring Boot Actuator's `/actuator/info` (auto-generates build info). Rejected — we're not using Spring Boot. The Gradle + properties approach is framework-agnostic and adds zero runtime dependencies.

### Priority 17: OpenAPI / Swagger documentation

**Problem:** There's no machine-readable API specification. The `README.md` documents endpoints with curl examples, but that's human-facing prose — not something a client SDK generator, API gateway, or contract-testing tool can consume. Internal consumers (dashboard frontend, CI/CD pipeline validation scripts, downstream services) currently reverse-engineer the API from the README or trial-and-error.

**Approach — two deliverables:**

**1. OpenAPI 3.1 specification (`openapi.yaml`)** — the source of truth, maintained alongside the code:

```yaml
openapi: "3.1.0"
info:
  title: Data-Flow-Filter
  version: "1.0.0-SNAPSHOT"
  description: Telemetry ingestion and query service for agricultural machinery
paths:
  /import:
    post:
      summary: Import CSV telemetry data
      requestBody:
        content:
          text/csv:
            schema:
              type: string
              format: binary
          multipart/form-data:
            schema:
              type: object
              properties:
                file:
                  type: string
                  format: binary
      responses:
        "200":
          description: Import successful
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ImportResponse"
        "400":
          description: Invalid request (empty body, malformed CSV)
        "500":
          description: Database error
  /query:
    post:
      summary: Query telemetry data with filters
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/FilterArray"
      responses:
        "200":
          description: Query results
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/QueryResponse"
        ...
components:
  schemas:
    Filter:
      type: object
      required: [field, value]
      properties:
        field: { type: string }
        operation: { type: string, enum: [Equals, LessThan, GreaterThan, Contains], default: Equals }
        value: { oneOf: [{ type: number }, { type: boolean }, { type: string }] }
    ImportResponse:
      type: object
      properties:
        status: { type: string, example: "ok" }
        importedRows: { type: integer }
        errors: { type: integer }
        metricCount: { type: integer }
    QueryResponse:
      type: object
      properties:
        results: { type: array, items: { $ref: "#/components/schemas/TelemetryEventResult" } }
        totalCount: { type: integer }
    ...
```

**What the spec covers:**
- All endpoints: `/import`, `/query`, `/health`, `/info`, `/live`, `/started`, `/ready`, `/version`
- Request schemas: `Filter` (with operation enum, polymorphic value), `FilterArray`
- Response schemas: `ImportResponse`, `QueryResponse`, `TelemetryEventResult`, `MetricValue`, `HealthStatus`, `AppInfo`, `VersionInfo`
- Error schemas: `ErrorResponse` (`{ status: "error", error: "message" }`)
- Content types: `text/csv`, `multipart/form-data`, `application/json`
- HTTP status codes with descriptions (200, 400, 405, 500, 503)
- Examples for each endpoint (realistic payloads from the test suite)
- Security scheme placeholder (API key header, documented but not enforced until Priority 12)

**2. Swagger UI served at `GET /swagger`** — interactive API explorer for developers:

- Embed **Swagger UI** (static HTML/JS, no server-side dependency) as a classpath resource
- The `/swagger` handler serves `swagger-ui.html` with the OpenAPI spec loaded from `GET /openapi.yaml`
- Also serve the raw spec at `GET /openapi.yaml` so tools can fetch it at runtime
- No build-time code generation — the YAML is hand-authored and stays in sync with the code

**Why hand-authored YAML over annotation-driven generation:**

| | Hand-authored YAML | Annotation-driven (e.g. SmallRye OpenAPI) |
|---|---|---|
| Runtime dependency | None | Annotation processor + runtime scanner |
| Framework coupling | Zero | Java annotation API |
| Spec quality | Full control, human-readable | Fragile, often requires overrides |
| Maintenance | Explicit — drift is obvious | Implicit — easy to forget updating |
| Build speed | No impact | Adds annotation processing time |

**Alternative considered:** SmallRye OpenAPI (annotation-based, framework-agnostic). Rejected — adds an annotation processor dependency and couples the API spec to Java source. Hand-authored YAML is the spec, the code implements it — not the other way around.

**Alternative considered:** SpringDoc OpenAPI (auto-generates from Spring MVC controllers). Rejected — requires Spring Boot, which we're deliberately avoiding.

**Who benefits:**
- **Internal frontend team** — auto-generate TypeScript types and fetch clients from the spec (`openapi-typescript`, `orval`)
- **QA team** — contract testing with `schemathesis` or `dredd` to catch API drift
- **API consumers** — Swagger UI for interactive exploration ("what fields does /query accept?")
- **CI/CD** — validate the spec is valid (`openapi-validator`) and that the spec matches the running instance (fetch `/openapi.yaml` and diff against repo)

**Keeping it in sync:** Add a CI step that starts the server, fetches `/openapi.yaml`, and diffs it against `openapi.yaml` in the repo. If they diverge, the build fails. This ensures the spec and implementation never drift.

### Priority 18: Containerization (Docker)

**Problem:** No `Dockerfile` or `docker-compose.yml`. Deploying the app requires a JDK install, Gradle build, and manual config on every target machine. Reproducing the environment across dev, staging, and production is error-prone — "works on my machine" is the default state.

**Approach — multi-stage Dockerfile:**

```dockerfile
# Stage 1: Build (Gradle + JDK 25)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY --chown=appuser:appuser gradle wrapper settings.gradle.kts build.gradle.kts ./
COPY --chown=appuser:appuser src ./src
RUN ./gradlew installDist --no-daemon

# Stage 2: Runtime (JRE only, no build tools)
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/install/data-flow-filter ./
# Database volume for persistence
VOLUME ["/app/DB"]
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/ready || exit 1
ENTRYPOINT ["./bin/data-flow-filter"]
```

**Why multi-stage:** The final image contains only the JRE (~180MB) and the app — no Gradle, no JDK compiler, no source code. Smaller attack surface, faster pulls.

**docker-compose.yml for local development:**

```yaml
services:
  data-flow-filter:
    build: .
    ports: ["8080:8080"]
    volumes:
      - db-data:/app/DB
      - ./config:/app/config
    environment:
      - SERVER_PORT=8080
volumes:
  db-data:
```

**Impact:**
- One-command deploy: `docker build -t data-flow-filter . && docker run -p 8080:8080 data-flow-filter`
- Health probes (Priority 14) are wired into `HEALTHCHECK` — Docker natively monitors `/ready`
- Database persistence via volume mount on `/app/DB`
- Config override via volume mount on `/config` or environment variables

**Alternative considered:** JLink custom runtime image. Rejected — adds build complexity for marginal size savings (JRE image is already ~180MB). Revisit if image size becomes a concern.

### Priority 19: CI/CD pipeline

**Problem:** No automated build, test, or deploy. Everything is manual — `./gradlew build`, `./test.sh`, `docker build`. No gate preventing broken code from reaching production. No automated regression detection.

**Approach — GitHub Actions (`.github/workflows/ci.yml`):**

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 25 }
      # Unit tests (fast, isolated)
      - run: ./gradlew test
      # Integration tests (full server + CSV import + query)
      - run: ./test.sh
      # Validate OpenAPI spec
      - run: |
          docker run --rm -v ${{ github.workspace }}:/spec \
            ghcr.io/openapi-contrib/openapi-validator:latest \
            /spec/openapi.yaml

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      # Build Docker image
      - uses: docker/build-push-action@v5
        with:
          push: false  # push only on main branch
          tags: data-flow-filter:${{ github.sha }}
```

**Pipeline stages:**
1. **Unit tests** — `./gradlew test` (fast, ~5s)
2. **Integration tests** — `./test.sh` (full server lifecycle, ~30s)
3. **OpenAPI spec validation** — ensure `openapi.yaml` is valid OpenAPI 3.1
4. **Docker build** — build the image, tag with git SHA
5. **Docker push** — push to registry (GHCR, ECR) on main branch only
6. **Deploy** — trigger deployment to staging/production (manual approval gate for production)

**Quality gates:**
- All unit tests must pass
- All integration tests must pass
- OpenAPI spec must be valid
- Docker build must succeed
- Code coverage threshold (e.g. 80% branch coverage after Priority 13)

**Why GitHub Actions over Jenkins/GitLab CI:** Zero infrastructure to maintain, free for public repos, native git integration. The pipeline definition lives in the repo (`.github/workflows/`) — no external configuration to drift.

**Alternative considered:** Gradle-only pipeline (no Docker in CI). Rejected — building the Docker image in CI catches container-specific issues (missing files, wrong permissions) before deployment.

### Priority 20: Graceful shutdown

**Problem:** `Startup.run()` has no shutdown hook. When the JVM receives SIGTERM (Docker stop, Kubernetes pod termination, `systemctl stop`), the following happens:
- In-flight HTTP requests are abruptly killed — clients get connection reset
- The thread pool is discarded without draining — queued requests never execute
- SQLite WAL file is left uncheckpointed — next startup must replay the WAL (slower)
- No cleanup logging — operators don't know if shutdown was clean

**Approach — `Runtime.addShutdownHook()`:**

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutdown initiated — stopping server gracefully...");

    // 1. Stop accepting new requests
    server.stop(0);  // 0 = don't wait, just stop accepting

    // 2. Drain in-flight requests (wait up to 30s)
    executor.shutdown();
    try {
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();  // force kill remaining
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
    }

    // 3. Checkpoint SQLite WAL (flush to main DB file)
    try (var conn = db.getConnection();
         var stmt = conn.createStatement()) {
        stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        log.info("SQLite WAL checkpointed successfully");
    } catch (SQLException e) {
        log.warn("WAL checkpoint failed: {}", e.getMessage());
    }

    log.info("Shutdown complete");
}));
```

**What this achieves:**
- **No dropped requests:** In-flight queries complete before shutdown
- **Clean WAL state:** `wal_checkpoint(TRUNCATE)` flushes the WAL to the main DB and deletes the WAL file — next startup is faster
- **Observable shutdown:** Log messages mark shutdown start and completion — operators can verify clean shutdown in logs
- **Bounded wait:** 30-second timeout prevents hanging forever if a query is stuck

**Docker integration:** Docker sends SIGTERM on `docker stop`, waits 10 seconds (configurable with `-t`), then sends SIGKILL. The 30-second drain timeout should be shorter than Docker's stop grace period. Configure with `docker stop -t 35` or `stopGracePeriod: 40s` in docker-compose.

**Kubernetes integration:** K8s sends SIGTERM on pod termination, waits `terminationGracePeriodSeconds` (default 30s), then SIGKILL. The shutdown hook fits within this window. For longer drain times, increase `terminationGracePeriodSeconds` in the pod spec.

### Priority 21: SQLite backup & restore

**Problem:** The entire data store is a single file (`DB/data_flow-filter.db`). No backup strategy means a corrupted file, disk failure, or accidental `rm` is total data loss. With millions of events, re-importing all CSVs from scratch is impractical.

**Approach — three layers of protection:**

**Layer 1: Hot backup via SQLite backup API (no DB lock):**

```java
public void backup(@NonNull Connection source, @NonNull String backupPath) throws SQLException {
    try (var backupConn = DriverManager.getConnection("jdbc:sqlite:" + backupPath)) {
        var backup = new org.sqlite.jdbc4.JDBC4Backup(backupConn, "main", source, "main");
        backup.step(-1);  // -1 = copy all pages at once
        if (backup.finish() != 0) {
            throw new SQLException("Backup failed");
        }
    }
}
```

- Uses `org.sqlite.jdbc4.JDBC4Backup` — copies the database page by page without locking readers
- Can run while queries are executing (WAL mode allows concurrent reads)
- Add a `POST /backup?path=/path/to/backup.db` endpoint for on-demand backup
- Schedule periodic backups via cron or systemd timer (e.g. daily at 2 AM)

**Layer 2: WAL checkpoint on schedule:**

```java
// Run periodically (e.g. every hour) to keep WAL file small
try (var conn = db.getConnection();
     var stmt = conn.createStatement();
     var rs = stmt.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")) {
    if (rs.next()) {
        var result = rs.getInt(0);  // 0=success, 1=busy, 2=lock
        log.info("WAL checkpoint result: {}", result == 0 ? "OK" : "busy");
    }
}
```

- `PRAGMA wal_checkpoint(PASSIVE)` — checkpoint if no writers are active, skip otherwise (non-blocking)
- Keeps the WAL file small — reduces recovery time after crash
- Run as a scheduled task or on the `/health` endpoint (passive, no impact on query latency)

**Layer 3: File-level backup (Docker volume):**

```bash
# Automated backup script (runs via cron)
#!/bin/bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
docker exec data-flow-filter \
  sqlite3 /app/DB/data_flow-filter.db \
  ".backup /app/DB/backup_${TIMESTAMP}.db"
# Copy to remote storage (S3, NFS, etc.)
docker cp data-flow-filter:/app/DB/backup_${TIMESTAMP}.db /backups/
```

**Restore procedure:**

```bash
# Stop the container
docker stop data-flow-filter
# Replace the DB with a backup
docker cp backup_20260724_020000.db data-flow-filter:/app/DB/data_flow-filter.db
# Remove WAL and SHM files (they'll be recreated)
docker exec data-flow-filter rm -f /app/DB/data_flow-filter.db-wal /app/DB/data_flow-filter.db-shm
# Start the container
docker start data-flow-filter
# Verify
curl http://localhost:8080/health
```

**Backup retention policy:** Keep daily backups for 7 days, weekly backups for 4 weeks, monthly backups for 12 months. Delete older backups automatically.

**Why not just rely on Docker volume snapshots:** Volume snapshots are infrastructure-dependent (EBS snapshots, ZFS clones) and may not be available in all environments. SQLite's native backup API works everywhere — Docker, bare metal, VM.

---

## Summary: What We'd Do Differently Before Shipping to Production

| Area | Current State | Production Target |
|------|--------------|-------------------|
| Pagination | Returns all results | Cursor-based pagination (Priority 1) |
| Caching | None | In-memory metric_definitions cache (Priority 2) |
| Observability | Console logs only | Micrometer + OTel + Grafana (Priority 3) |
| Import throughput | In-memory parse, 500-row batches | Streaming parse + tuned batch size (Priority 4, 8) |
| Query concurrency | 4 threads | Virtual threads (Priority 5, 7) |
| DB indexes | Generic | Profiled and targeted (Priority 6) |
| Connection mgmt | New connection per call | Shared read connection (Priority 9) |
| API surface | 4 endpoints | Metadata endpoints + import progress (Priority 10) |
| Data lifecycle | Unbounded growth | Archival + purge strategy (Priority 11) |
| Security | None | API key auth + size limits + TLS (Priority 12) |
| Testing | Integration only (test.sh) | Unit tests for all business logic (Priority 13) |
| Health probes | Single /health (stats only) | /live, /started, /ready, /health (Priority 14) |
| App info | Raw Config (6 fields) | Structured: app, config, DB, JVM, stats (Priority 15) |
| Versioning | None | /version with git commit + build metadata (Priority 16) |
| API docs | README prose only | OpenAPI 3.1 spec + Swagger UI (Priority 17) |
| Containerization | Manual JDK + Gradle build | Docker multi-stage image (Priority 18) |
| CI/CD | Manual build, test, deploy | GitHub Actions: test → build → deploy (Priority 19) |
| Shutdown | Abrupt (no hook) | Graceful: drain requests + WAL checkpoint (Priority 20) |
| Data safety | Single DB file, no backup | Hot backup + WAL checkpoint + restore procedure (Priority 21) |

The lowest-effort, highest-impact changes are **Priority 2** (metric cache — 10 lines), **Priority 7** (virtual threads — 1 line), **Priority 8** (streaming CSV — 5 lines), **Priority 16** (/version — Gradle task + one handler), **Priority 17** (OpenAPI spec — hand-authored YAML), and **Priority 20** (graceful shutdown — one shutdown hook). The highest-value investments are **Priority 1** (pagination), **Priority 3** (observability), **Priority 6** (index tuning), **Priority 13** (unit tests), **Priority 18** (Docker), and **Priority 19** (CI/CD).
