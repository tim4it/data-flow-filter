# Data flow + filter

Lightweight HTTP service that imports CSV telemetry data from agricultural machinery (tractors, combines) into a SQLite database and exposes a filtered query API. Built with Java 25, zero external servers required.

## Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| JDK | 25+ | Compilation and runtime |
| Gradle | 8+ | Build system (wrapper included) |
| SQLite | 3.x | Database (JDBC driver bundled, CLI optional for manual inspection) |

## Project Description

Data-flow-filter is a standalone telemetry ingestion and query service designed for John Deere agricultural equipment data. It:

1. **Accepts CSV files** from tractor/combine telematics systems
2. **Normalizes** column headers (e.g. `"GPS longitude [°]"` -> `"GpsLongitude"`)
3. **Infers** metric data types (NUMBER, BOOLEAN, STRING) from header patterns and sample values
4. **Stores** data in an Entity-Attribute-Value (EAV) schema so new metrics require zero schema changes
5. **Serves** a JSON query API with type-aware filtering

### Architecture

```
Client (browser / script)
    |
    v
+-----------------+     +----------------------+     +--------------------+     +---------------------+
|  HTTP Server    |     |  Handler Layer       |     |  Service Layer     |     |  SQLite DB          |
|  (Jetty-less,   |     |                      |     |                    |     |                     |
|  java.net.http) |     |  ImportHandler       |     |  CSVImportService  |     |  machines           |
|                 |---->|  QueryHandler        |---->|  SQLiteDB          |---->|  telemetry_events   |
|  Endpoints:     |     |  (CORS, error mgmt)  |     |  (EAV operations)  |     |                     |
|  POST /import   |     +----------------------+     +--------------------+     |  telemetry_metrics  |
|  POST /query    |                                                             |                     |
|  GET  /health   |                                                             +---------------------+
|  GET  /info     |
+-----------------+
```

**Key design decisions:**
- **No web framework** -- uses `com.sun.net.httpserver` (built into the JDK)
- **EAV storage** -- dynamic metrics stored as key-value pairs, no ALTER TABLE needed
- **Flyway migrations** -- schema versioned and auto-applied on startup
- **Lombok** -- eliminates boilerplate (`@NonNull`, `@Getter`, records)

## Database Model

```
+----------------------+         +----------------------+         +----------------------+
|        machines      |         |   telemetry_events   |         |   telemetry_metrics  |
+----------------------+         +----------------------+         +----------------------+
| id            PK     |<--------| id            PK     |<--------| event_id       PK,FK |
| serial_number  UK    |  1:N    | machine_id    FK     |   1:N   | metric_name    PK,FK |
| vehicle_type         |         | recorded_at          |         | num_value            |
+----------------------+         | latitude             |         | str_value            |
                                 | longitude            |         +----------------------+
                                 | ground_speed         |
                                 +----------------------+

+----------------------+
| metric_definitions   |  (standalone registry)
+----------------------+
| metric_name   PK     |
| data_type            |
| unit                 |
| description          |
+----------------------+

  metric_definitions  -- registry of all known metrics (name, type, unit)
  telemetry_metrics.metric_name  -- references metric_definitions.metric_name (no FK enforced)

Indexes:
  idx_metrics_name      ON telemetry_metrics(metric_name)
  idx_metrics_num       ON telemetry_metrics(metric_name, num_value)
  idx_metrics_str       ON telemetry_metrics(metric_name, str_value)
  idx_events_recorded_at ON telemetry_events(recorded_at)
  idx_events_machine    ON telemetry_events(machine_id)
```

### Tables Explained

| Table | Purpose |
|-------|---------|
| **metric_definitions** | Schema registry -- defines each metric's name, data type, unit, and original CSV header |
| **machines** | Machine registry -- one row per serial number, auto-created on first import |
| **telemetry_events** | Core event data -- timestamp, GPS coords, ground speed, linked to a machine |
| **telemetry_metrics** | Dynamic EAV metrics -- each row is one metric value for one event (num_value or str_value populated) |

### Schema Flexibility

The EAV design means **you can add new vehicle types or machines without any database schema changes**. All machines share the same `telemetry_events` table for core fields (timestamp, GPS, ground speed). Each machine's dynamic metrics are stored as rows in `telemetry_metrics` -- so a tractor might report `EngineHours` and `HydraulicPressure`, while a combine reports `GrainTankUnloading` and `HeaderHeight`, all in the same tables. New metrics are auto-registered in `metric_definitions` on first import; no ALTER TABLE needed.

## How It Works

### CSV Import Flow (`POST /import`)

```
CSV File
  |
  v  +-------------------+
  +->| 1. Parse CSV      |  (univocity-parsers, semicolon delimiter)
  |   +------------------+
  v          |
     +-------v-------+
     | 2. Normalize  |  "GPS longitude [°]"  ->  "GpsLongitude"
     |    headers    |  "Grain tank [I/O]"   ->  "GrainTankUnloading"
     +---------------+
          |
          v
     +---------------+
     | 3. Register   |  New metrics auto-registered in metric_definitions
     |    metrics    |  Type inferred: [I/O] -> BOOLEAN, sample values -> NUMBER/STRING
     +---------------+
          |
          v
     +-----------------+
     | 4. Batch        |  Core fields -> telemetry_events
     |    insert       |  Dynamic fields -> telemetry_metrics (EAV)
     |    (500/commit) |  Machines auto-created by serial number
     +-----------------+
          |
          v
     JSON: { importedRows: N, errors: M, metricCount: K }
```

### Query Flow (`POST /query`)

```
JSON Filters: [ { "field": "GroundSpeed", "operation": "GreaterThan", "value": 4.5 } ]
  |
  v  +-------------------+
  +->| 1. Validate       |  Field exists? Operation valid for data type?
  |   +------------------+
  v          |
     +-------v--------+
     | 2. Classify    |  Core field (telemetry_events) vs EAV metric
     |    filters     |
     +----------------+
          |
          v
     +----------------+
     | 3. Two-phase   |  Phase 1: Find matching event IDs (subquery per metric)
     |    SQL query   |  Phase 2: Fetch full event + all metrics for those IDs
     +----------------+
          |
          v
     JSON: { results: [ { eventId, serialNumber, latitude, ..., metrics: [...] } ], totalCount: N }
```

### Supported Filter Operations

| Data Type | Operations |
|-----------|-----------|
| NUMBER | `Equals`, `LessThan`, `GreaterThan` |
| BOOLEAN | `Equals` |
| STRING | `Equals`, `Contains` |
| DATE | `Equals`, `LessThan`, `GreaterThan`, `Contains` |

## Build & Run

### Build

```bash
./gradlew build
```

### Run

```bash
./gradlew run
```

The server starts on port 8080 (configurable via `server.port`).

### Build a Fat JAR

```bash
# Add the shadow plugin to build.gradle.kts if needed, or use:
./gradlew installDist
./build/install/data-flow-filter/bin/data-flow-filter
```

## Configuration

Defaults in `src/main/resources/application.properties`. Override by creating `config/application.properties` next to the executable, or pass system properties:

```bash
./gradlew run --args="-Dserver.port=9090 -Ddatabase.path=/var/lib/df.db"
```

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8080 | HTTP listen port |
| `server.thread-pool-size` | 4 | Worker thread count |
| `database.path` | `DB/data_flow-filter.db` | SQLite file path (relative to cwd) |
| `cors.allow-origins` | `*` | CORS allowed origins |
| `cors.allow-methods` | `GET, POST, OPTIONS` | CORS allowed methods |
| `cors.allow-headers` | `Content-Type` | CORS allowed headers |

## API Reference

### Import CSV

```bash
curl -X POST http://localhost:8080/import \
  -H "Content-Type: text/csv" \
  --data-binary @tractor_data.csv
```

**Response:**
```json
{ "status": "ok", "importedRows": 1250, "errors": 0, "metricCount": 42 }
```

Multipart upload is also supported:
```bash
curl -X POST http://localhost:8080/import \
  -F "file=@tractor_data.csv"
```

### Query Telemetry

```bash
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -d '[
    { "field": "GroundSpeed", "operation": "GreaterThan", "value": 4.5 },
    { "field": "GrainTankUnloading", "value": true }
  ]'
```

**Response:**
```json
{
  "results": [
    {
      "eventId": 42,
      "serialNumber": "A123456",
      "vehicleType": "TRACTOR",
      "recordedAt": "2025-07-23T10:30:00",
      "latitude": 46.5547,
      "longitude": 14.5057,
      "groundSpeed": 5.2,
      "metrics": [
        { "name": "GrainTankUnloading", "numValue": 1.0, "strValue": null },
        { "name": "EngineHours", "numValue": 1247.5, "strValue": null }
      ]
    }
  ],
  "totalCount": 1
}
```

### Health Check

```bash
curl http://localhost:8080/health
```

**Response:**
```json
{ "status": "ok", "machines": 3, "events": 12500 }
```

### Configuration Info

```bash
curl http://localhost:8080/info
```

**Response:**
```json
{ "serverPort": 8080, "serverThreadPoolSize": 4, "databasePath": "DB/data_flow-filter.db", ... }
```

## Testing

### Run Unit Tests

```bash
./gradlew test
```

### Manual Testing with a Fresh Database

The easiest way to test end-to-end is with an empty SQLite database:

```bash
# 1. Start with a clean database
rm -f DB/data_flow-filter.db

# 2. Start the server
./gradlew run

# 3. Check health (should show 0 machines, 0 events)
curl http://localhost:8080/health
# {"status":"ok","machines":0,"events":0}

# 4. Import a CSV file
curl -X POST http://localhost:8080/import \
  -H "Content-Type: text/csv" \
  --data-binary @sample_data.csv

# 5. Query all data (empty filter = return everything)
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -d '[]'

# 6. Query with filters
curl -X POST http://localhost:8080/query \
  -H "Content-Type: application/json" \
  -d '[{ "field": "vehicleType", "value": "TRACTOR" }]'

# 7. Inspect the database directly (requires sqlite3 CLI)
sqlite3 DB/data_flow-filter.db

sqlite> SELECT COUNT(*) FROM machines;
sqlite> SELECT COUNT(*) FROM telemetry_events;
sqlite> SELECT * FROM metric_definitions LIMIT 5;
sqlite> SELECT te.recorded_at, m.serial_number, tm.metric_name, tm.num_value
       FROM telemetry_metrics tm
       JOIN telemetry_events te ON tm.event_id = te.id
       JOIN machines m ON te.machine_id = m.id
       LIMIT 10;
```

### Inspect Database Schema

```bash
sqlite3 DB/data_flow-filter.db ".schema"
```
