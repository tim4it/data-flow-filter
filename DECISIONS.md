# Design Decisions & Optimization Roadmap

Walking through the architectural choices that shaped **data-flow-filter**, the trade-offs we weighed, alternatives we rejected, and the roadmap for turning this into a fully optimized production pipeline.

---

## Architecture Decisions (Made)

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

## Optimization Roadmap (If We Have More Time)

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

The lowest-effort, highest-impact changes are **Priority 2** (metric cache — 10 lines), **Priority 7** (virtual threads — 1 line), and **Priority 8** (streaming CSV — 5 lines). The highest-value investments are **Priority 1** (pagination), **Priority 3** (observability), and **Priority 6** (index tuning).