-- 1. Schema registry for metric definitions
CREATE TABLE metric_definitions (
    metric_name TEXT PRIMARY KEY,
    data_type TEXT NOT NULL CHECK(data_type IN ('NUMBER', 'BOOLEAN', 'STRING')),
    unit TEXT,
    description TEXT
);

-- 2. Core Machine Entity
CREATE TABLE machines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_number TEXT UNIQUE NOT NULL,
    vehicle_type TEXT NOT NULL CHECK(vehicle_type IN ('TRACTOR', 'COMBINE', 'SPRAYER'))
);

-- 3. Base Telemetry Event (Core fields shared by all vehicles)
CREATE TABLE telemetry_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    machine_id INTEGER NOT NULL REFERENCES machines(id),
    recorded_at DATETIME NOT NULL,
    latitude REAL,
    longitude REAL,
    ground_speed REAL
);

-- 4. Dynamic Telemetry Metrics (EAV key-value pairs)
CREATE TABLE telemetry_metrics (
    event_id INTEGER NOT NULL REFERENCES telemetry_events(id) ON DELETE CASCADE,
    metric_name TEXT NOT NULL,
    num_value REAL,
    str_value TEXT,
    PRIMARY KEY (event_id, metric_name)
);

-- Indexes for efficient querying
CREATE INDEX idx_metrics_name ON telemetry_metrics(metric_name);
CREATE INDEX idx_metrics_num ON telemetry_metrics(metric_name, num_value);
CREATE INDEX idx_metrics_str ON telemetry_metrics(metric_name, str_value);
CREATE INDEX idx_events_recorded_at ON telemetry_events(recorded_at);
CREATE INDEX idx_events_machine ON telemetry_events(machine_id);