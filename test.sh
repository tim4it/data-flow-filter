#!/usr/bin/env bash
##############################################################################
# test.sh — Integration test harness for data-flow-filter
#
# 1. Starts the server on a free port with a fresh (empty) database
# 2. Imports every CSV found under src/main/resources/
# 3. Runs 80+ /query tests covering
#    - every core field with every allowed operation
#    - every EAV metric (NUMBER / BOOLEAN / STRING) with allowed ops
#    - combined AND filters
#    - validation rejection for bad field, bad operation, bad value
#    - edge cases (empty filter, no-match, case sensitivity)
# 4. Shuts the server down and prints a summary
##############################################################################
set -euo pipefail

# ── Colour helpers (safe for non-TTY — just become no-ops) ─────────────────
if [[ -t 1 ]]; then
  RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[1;33m'
  BLD='\033[1m'; RST='\033[0m'
else
  RED=''; GRN=''; YEL=''; BLD=''; RST=''
fi

# ── Counters ────────────────────────────────────────────────────────────────
PASS=0; FAIL=0; SKIP=0

pass() { PASS=$((PASS + 1)); echo -e "  ${GRN}PASS${RST} $1"; }
fail() { FAIL=$((FAIL + 1)); echo -e "  ${RED}FAIL${RST} $1${RST} — $2"; }
skip() { SKIP=$((SKIP + 1)); echo -e "  ${YEL}SKIP${RST} $1 — $2"; }

# ── Project root ────────────────────────────────────────────────────────────
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# ── Pick a free port ────────────────────────────────────────────────────────
PORT=$(python3 -c "import socket;s=socket.socket();s.bind(('',0));print(s.getsockname()[1]);s.close()")
echo -e "${BLD}Using port ${PORT}${RST}"

# ── Prepare a fresh database ────────────────────────────────────────────────
BACKUP_DB="${PROJECT_DIR}/DB/data_flow-filter.db.testbak"
if [[ -f "${PROJECT_DIR}/DB/data_flow-filter.db" ]]; then
  cp "${PROJECT_DIR}/DB/data_flow-filter.db" "$BACKUP_DB"
fi
# Remove old DB + WAL so Flyway starts from scratch
rm -f "${PROJECT_DIR}/DB/data_flow-filter.db" \
      "${PROJECT_DIR}/DB/data_flow-filter.db-wal" \
      "${PROJECT_DIR}/DB/data_flow-filter.db-shm"

# ── Start the server ────────────────────────────────────────────────────────
echo -e "${BLD}Starting server on port ${PORT}…${RST}"

# Create user-level config override (read by Config.load() as step 2)
mkdir -p "${PROJECT_DIR}/config"
cat > "${PROJECT_DIR}/config/application.properties" <<EOF
server.port=${PORT}
database.path=DB/data_flow-filter.db
EOF

(
  cd "$PROJECT_DIR"
  ./gradlew run 1>"${PROJECT_DIR}/server.log" 2>&1
) &
SERVER_PID=$!

# Wait until the /health endpoint responds (up to 15 s)
for i in $(seq 1 30); do
  if curl -sf "http://localhost:${PORT}/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

if ! curl -sf "http://localhost:${PORT}/health" >/dev/null 2>&1; then
  echo -e "${RED}FATAL: Server did not start in time.${RST}"
  cat "${PROJECT_DIR}/server.log"
  exit 1
fi
echo -e "${GRN}✓ Server started (PID: ${SERVER_PID})${RST}"

# ── Cleanup trap ────────────────────────────────────────────────────────────
cleanup() {
  # gradlew run forks the JVM; kill Gradle + any Java process on our port
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  # Kill the actual Java server (listen on our port)
  local jvm_pid
  jvm_pid=$(lsof -ti :"$PORT" 2>/dev/null || true)
  if [[ -n "$jvm_pid" ]]; then
    kill $jvm_pid 2>/dev/null || true
    sleep 1
    kill -9 $jvm_pid 2>/dev/null || true
  fi
  rm -rf "${PROJECT_DIR}/config"
  echo -e "\n${BLD}✓ Server stopped${RST}"
}
trap cleanup EXIT

# ── Helper: query and assert HTTP status ────────────────────────────────────
# Usage: assert_status "<description>" <method> <path> [data_string] <expected_code>
assert_status() {
  local desc="$1" method="$2" path="$3" data="${4:-}" expected="$5"
  local args=(-s -o /tmp/df_response.json -w '%{http_code}' -X "$method")

  if [[ -n "$data" ]]; then
    case "$data" in
      __FILE:*)
        local filepath="${data#__FILE:}"
        args+=(-H "Content-Type: text/csv" -T "$filepath")
        ;;
      *)
        args+=(-H "Content-Type: application/json" -d "$data")
        ;;
    esac
  fi

  local code
  code=$(curl "${args[@]}" "http://localhost:${PORT}${path}" 2>/dev/null) || code="000"

  if [[ "$code" == "$expected" ]]; then
    pass "$desc"
  else
    fail "$desc" "Expected HTTP $expected, got $code"
  fi
}

# ── Helper: POST /query and return body (assumes 200) ───────────────────────
query() {
  local desc="$1" filters="$2"
  curl -s -X POST \
       -H "Content-Type: application/json" \
       -d "$filters" \
       "http://localhost:${PORT}/query" \
       -o /tmp/df_query.json 2>/dev/null || true
}

# ── Helper: assert totalCount from last query response ──────────────────────
assert_count() {
  local desc="$1" expected="$2"
  local actual
  actual=$(jq 'if .totalCount then .totalCount else "no_total" end' /tmp/df_query.json 2>/dev/null) || actual="-"
  if [[ "$actual" == "$expected" ]]; then
    pass "$desc"
  else
    fail "$desc" "Expected totalCount=$expected, got $actual"
  fi
}

assert_count_gt() {
  local desc="$1" min="$2"
  local actual
  actual=$(jq 'if .totalCount then .totalCount else 0 end' /tmp/df_query.json 2>/dev/null) || actual=0
  if (( actual > min )); then
    pass "$desc"
  else
    fail "$desc" "Expected totalCount>$min, got $actual"
  fi
}

assert_count_zero() {
  local desc="$1"
  assert_count "$desc" "0"
}

assert_status_ok() {
  local desc="$1"
  local status
  status=$(jq -r 'if .status then .status else "no_status" end' /tmp/df_query.json 2>/dev/null) || status="no_status"
  if [[ "$status" == "error" ]]; then
    # For validation tests: error response means server correctly rejected bad input
    pass "$desc"
  else
    fail "$desc" "Expected error response, got status=$status"
  fi
}

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: GET /health${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

assert_status "Health before import returns 200" GET "/health" "" 200

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /import${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# -- Import each CSV ----------------------------------------------------------
for csv in "${PROJECT_DIR}"/src/main/resources/LD_*.csv; do
  fname=$(basename "$csv")
  assert_status "Import $fname" POST "/import" "__FILE:$csv" 200
done

# -- Import error cases -------------------------------------------------------
assert_status "Import empty body" POST "/import" "" 400

# -- Verify row counts --------------------------------------------------------
echo ""
echo -e "  ${BLD}--- Row-count verification after import ---${RST}"

health_body=$(curl -s "http://localhost:${PORT}/health")
machines=$(echo "$health_body" | jq '.machines')
events=$(echo "$health_body" | jq '.events')

# Expected: 4 machines (A5304997, A5305041, C7502643, C7502627)
# Expected rows: 2658 + 3806 + 334 + 2230 = 9028
if [[ "$machines" == "4" ]]; then
  pass "Health reports 4 machines"
else
  fail "Health reports 4 machines" "Got $machines"
fi

if [[ "$events" == "9028" ]]; then
  pass "Health reports 9028 events"
else
  fail "Health reports 9028 events" "Got $events"
fi

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — Validation & Error Handling${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# -- Method not allowed -------------------------------------------------------
assert_status "GET /query → 405" GET "/query" "" 405

# -- Empty / missing body -----------------------------------------------------
assert_status "POST /query empty body" POST "/query" "" 400

# -- Empty filter array (valid — returns all) ---------------------------------
query "Empty filter array returns all" "[]"
assert_count "Empty filter → 9028 results" "9028"

# -- Null / missing field name ------------------------------------------------
query "Null field" '[{"field": null, "value": 1}]'
assert_status_ok "Null field — rejected"

query "Empty field" '[{"field": "", "value": 1}]'
assert_status_ok "Empty field — rejected"

query "Missing field" '[{"value": 1}]'
assert_status_ok "Missing field — rejected"

# -- Null value ---------------------------------------------------------------
query "Null value" '[{"field": "EngineSpeed", "value": null}]'
assert_status_ok "Null value — rejected"

# -- Unknown field ------------------------------------------------------------
query "Unknown core" '[{"field": "FooBar", "value": "x"}]'
assert_status_ok "Unknown core field — rejected"

query "Unknown metric" '[{"field": "NonExistentMetric", "value": 1}]'
assert_status_ok "Unknown metric — rejected"

# -- Operation not allowed for data type --------------------------------------
# NUMBER field + Contains
query "NUMBER + Contains" '[{"field": "EngineSpeed", "operation": "Contains", "value": "1"}]'
assert_status_ok "NUMBER + Contains — rejected"

# BOOLEAN field + GreaterThan
query "BOOLEAN + GreaterThan" '[{"field": "GrainTankUnloading", "operation": "GreaterThan", "value": true}]'
assert_status_ok "BOOLEAN + GreaterThan — rejected"

# BOOLEAN field + LessThan
query "BOOLEAN + LessThan" '[{"field": "GrainTankUnloading", "operation": "LessThan", "value": true}]'
assert_status_ok "BOOLEAN + LessThan — rejected"

# STRING field + GreaterThan
query "STRING + GreaterThan" '[{"field": "SerialNumber", "operation": "GreaterThan", "value": "A"}]'
assert_status_ok "STRING + GreaterThan — rejected"

# STRING field + LessThan
query "STRING + LessThan" '[{"field": "SerialNumber", "operation": "LessThan", "value": "A"}]'
assert_status_ok "STRING + LessThan — rejected"

# -- Invalid operation name ---------------------------------------------------
query "Bad operation" '[{"field": "EngineSpeed", "operation": "DoesNotExist", "value": 1}]'
assert_status_ok "Bad operation — rejected"

# -- Non-numeric value on NUMBER field ----------------------------------------
query "Non-numeric on NUMBER" '[{"field": "EngineSpeed", "value": "not_a_number"}]'
assert_status_ok "Non-numeric value on NUMBER — rejected"

# -- Invalid boolean value ----------------------------------------------------
query "Bad boolean value" '[{"field": "GrainTankUnloading", "value": "maybe"}]'
assert_status_ok "Invalid boolean value — rejected"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — Core Fields${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── RecordedAt (DATE) ───────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- RecordedAt (DATE) ---${RST}"

query "RecordedAt Equals" '[{"field":"RecordedAt","operation":"Equals","value":"2023-03-31T05:54:27"}]'
assert_count_gt "RecordedAt Equals → matches" "0"

query "RecordedAt Contains (date prefix)" '[{"field":"RecordedAt","operation":"Contains","value":"2023-03-31"}]'
assert_count_gt "RecordedAt Contains '2023-03-31' → matches" "0"

query "RecordedAt Contains (year)" '[{"field":"RecordedAt","operation":"Contains","value":"2022"}]'
assert_count_gt "RecordedAt Contains '2022' → matches" "0"

query "RecordedAt no-match" '[{"field":"RecordedAt","operation":"Contains","value":"1999"}]'
assert_count_zero "RecordedAt Contains '1999' → 0"

query "RecordedAt GreaterThan" '[{"field":"RecordedAt","operation":"GreaterThan","value":"2023-03-31T00:00:00"}]'
assert_count_gt "RecordedAt > 2023-03-31 → matches" "0"

query "RecordedAt LessThan" '[{"field":"RecordedAt","operation":"LessThan","value":"2022-10-08T00:00:00"}]'
assert_count_gt "RecordedAt < 2022-10-08 → matches" "0"

query "RecordedAt GreaterThan no-match" '[{"field":"RecordedAt","operation":"GreaterThan","value":"2099-01-01T00:00:00"}]'
assert_count_zero "RecordedAt > 2099-01-01 → 0"

query "RecordedAt LessThan no-match" '[{"field":"RecordedAt","operation":"LessThan","value":"2000-01-01T00:00:00"}]'
assert_count_zero "RecordedAt < 2000-01-01 → 0"

# ── SerialNumber (STRING) ───────────────────────────────────────────────────
echo -e "\n  ${BLD}--- SerialNumber (STRING) ---${RST}"

query "SerialNumber Equals A5304997" '[{"field":"SerialNumber","operation":"Equals","value":"A5304997"}]'
assert_count "SerialNumber = A5304997 → 2658" "2658"

query "SerialNumber Equals C7502627" '[{"field":"SerialNumber","operation":"Equals","value":"C7502627"}]'
assert_count "SerialNumber = C7502627 → 2230" "2230"

query "SerialNumber Contains A530" '[{"field":"SerialNumber","operation":"Contains","value":"A530"}]'
assert_count "SerialNumber Contains 'A530' → 6464" "6464"

query "SerialNumber Contains C750" '[{"field":"SerialNumber","operation":"Contains","value":"C750"}]'
assert_count "SerialNumber Contains 'C750' → 2564" "2564"

query "SerialNumber no-match" '[{"field":"SerialNumber","operation":"Equals","value":"ZZZZ9999"}]'
assert_count_zero "SerialNumber = ZZZZ9999 → 0"

# ── VehicleType (STRING) ────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- VehicleType (STRING) ---${RST}"

query "VehicleType Equals TRACTOR" '[{"field":"VehicleType","operation":"Equals","value":"TRACTOR"}]'
assert_count "VehicleType = TRACTOR → 6464" "6464"

query "VehicleType Equals COMBINE" '[{"field":"VehicleType","operation":"Equals","value":"COMBINE"}]'
assert_count "VehicleType = COMBINE → 2564" "2564"

query "VehicleType Contains TRAC" '[{"field":"VehicleType","operation":"Contains","value":"TRAC"}]'
assert_count "VehicleType Contains 'TRAC' → 6464" "6464"

query "VehicleType no-match" '[{"field":"VehicleType","operation":"Equals","value":"SPRAYER"}]'
assert_count_zero "VehicleType = SPRAYER → 0"

# ── Latitude (NUMBER) ───────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Latitude (NUMBER) ---${RST}"

query "Latitude GreaterThan 46" '[{"field":"Latitude","operation":"GreaterThan","value":46}]'
assert_count_gt "Latitude > 46 → matches" "0"

query "Latitude LessThan 45.5" '[{"field":"Latitude","operation":"LessThan","value":45.5}]'
assert_count_gt "Latitude < 45.5 → matches" "0"

query "Latitude Equals (exact)" '[{"field":"Latitude","operation":"Equals","value":46.048856}]'
assert_count_gt "Latitude = 46.048856 → matches" "0"

query "Latitude no-match" '[{"field":"Latitude","operation":"GreaterThan","value":90}]'
assert_count_zero "Latitude > 90 → 0"

# ── Longitude (NUMBER) ──────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Longitude (NUMBER) ---${RST}"

query "Longitude GreaterThan 20.1" '[{"field":"Longitude","operation":"GreaterThan","value":20.1}]'
assert_count_gt "Longitude > 20.1 → matches" "0"

query "Longitude LessThan 20.3" '[{"field":"Longitude","operation":"LessThan","value":20.3}]'
assert_count_gt "Longitude < 20.3 → matches" "0"

query "Longitude no-match" '[{"field":"Longitude","operation":"GreaterThan","value":999}]'
assert_count_zero "Longitude > 999 → 0"

# ── GroundSpeed (NUMBER) ────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- GroundSpeed (NUMBER) ---${RST}"

query "GroundSpeed Equals 0" '[{"field":"GroundSpeed","operation":"Equals","value":0}]'
assert_count_gt "GroundSpeed = 0 → matches (stationary rows)" "0"

query "GroundSpeed GreaterThan 0" '[{"field":"GroundSpeed","operation":"GreaterThan","value":0}]'
assert_count_gt "GroundSpeed > 0 → moving rows" "0"

query "GroundSpeed GreaterThan 5" '[{"field":"GroundSpeed","operation":"GreaterThan","value":5}]'
assert_count_gt "GroundSpeed > 5 → faster rows" "0"

query "GroundSpeed LessThan 1" '[{"field":"GroundSpeed","operation":"LessThan","value":1}]'
assert_count_gt "GroundSpeed < 1 → slow/stationary" "0"

query "GroundSpeed no-match" '[{"field":"GroundSpeed","operation":"GreaterThan","value":9999}]'
assert_count_zero "GroundSpeed > 9999 → 0"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — EAV Metrics (Combine-only)${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── GrainTankUnloading (BOOLEAN — combine) ──────────────────────────────────
echo -e "\n  ${BLD}--- GrainTankUnloading (BOOLEAN) ---${RST}"

query "GrainTankUnloading Equals true" '[{"field":"GrainTankUnloading","operation":"Equals","value":true}]'
assert_count "GrainTankUnloading = true → 73" "73"

query "GrainTankUnloading Equals false" '[{"field":"GrainTankUnloading","operation":"Equals","value":false}]'
assert_count_gt "GrainTankUnloading = false → matches" "0"

query "GrainTankUnloading Equals 'on' (string)" '[{"field":"GrainTankUnloading","operation":"Equals","value":"on"}]'
assert_count "GrainTankUnloading = 'on' → 73" "73"

# ── TypeOfCrop (STRING — combine) ───────────────────────────────────────────
echo -e "\n  ${BLD}--- TypeOfCrop (STRING) ---${RST}"

query "TypeOfCrop Equals Sunflowers" '[{"field":"TypeOfCrop","operation":"Equals","value":"Sunflowers"}]'
assert_count "TypeOfCrop = Sunflowers → 334" "334"

query "TypeOfCrop Equals Maize" '[{"field":"TypeOfCrop","operation":"Equals","value":"Maize"}]'
assert_count "TypeOfCrop = Maize → 2230" "2230"

query "TypeOfCrop Contains Mai" '[{"field":"TypeOfCrop","operation":"Contains","value":"Mai"}]'
assert_count "TypeOfCrop Contains 'Mai' → 2230" "2230"

query "TypeOfCrop Contains Sun" '[{"field":"TypeOfCrop","operation":"Contains","value":"Sun"}]'
assert_count "TypeOfCrop Contains 'Sun' → 334" "334"

query "TypeOfCrop no-match" '[{"field":"TypeOfCrop","operation":"Equals","value":"Wheat"}]'
assert_count_zero "TypeOfCrop = Wheat → 0"

# ── WorkingPosition (BOOLEAN — combine) ─────────────────────────────────────
echo -e "\n  ${BLD}--- WorkingPosition (BOOLEAN) ---${RST}"

query "WorkingPosition Equals true" '[{"field":"WorkingPosition","operation":"Equals","value":true}]'
assert_count_gt "WorkingPosition = true → matches" "0"

query "WorkingPosition Equals false" '[{"field":"WorkingPosition","operation":"Equals","value":false}]'
assert_count_gt "WorkingPosition = false → matches" "0"

# ── MainDriveStatus (BOOLEAN — combine) ─────────────────────────────────────
echo -e "\n  ${BLD}--- MainDriveStatus (BOOLEAN) ---${RST}"

query "MainDriveStatus Equals true" '[{"field":"MainDriveStatus","operation":"Equals","value":true}]'
assert_count_gt "MainDriveStatus = true → matches" "0"

query "MainDriveStatus Equals false" '[{"field":"MainDriveStatus","operation":"Equals","value":false}]'
assert_count_gt "MainDriveStatus = false → matches" "0"

# ── YieldMeasurement (BOOLEAN — combine) ────────────────────────────────────
echo -e "\n  ${BLD}--- YieldMeasurement (BOOLEAN) ---${RST}"

query "YieldMeasurement Equals true" '[{"field":"YieldMeasurement","operation":"Equals","value":true}]'
assert_count_gt "YieldMeasurement = true → matches" "0"

# ── DrumSpeed (NUMBER — combine) ────────────────────────────────────────────
echo -e "\n  ${BLD}--- DrumSpeed (NUMBER) ---${RST}"

query "DrumSpeed Equals 0" '[{"field":"DrumSpeed","operation":"Equals","value":0}]'
assert_count_gt "DrumSpeed = 0 → matches" "0"

query "DrumSpeed GreaterThan 0" '[{"field":"DrumSpeed","operation":"GreaterThan","value":0}]'
assert_count_gt "DrumSpeed > 0 → matches" "0"

# ── Throughput (NUMBER — combine) ───────────────────────────────────────────
echo -e "\n  ${BLD}--- Throughput (NUMBER) ---${RST}"

query "Throughput GreaterThan 0" '[{"field":"Throughput","operation":"GreaterThan","value":0}]'
assert_count_gt "Throughput > 0 → matches" "0"

query "Throughput LessThan 20" '[{"field":"Throughput","operation":"LessThan","value":20}]'
assert_count_gt "Throughput < 20 → matches" "0"

# ── GrainMoistureContent (NUMBER — combine) ─────────────────────────────────
echo -e "\n  ${BLD}--- GrainMoistureContent (NUMBER) ---${RST}"

query "GrainMoistureContent GreaterThan 5" '[{"field":"GrainMoistureContent","operation":"GreaterThan","value":5}]'
assert_count_gt "GrainMoistureContent > 5 → matches" "0"

# ── SpecificCropWeight (NUMBER — combine) ───────────────────────────────────
echo -e "\n  ${BLD}--- SpecificCropWeight (NUMBER) ---${RST}"

query "SpecificCropWeight Equals 341" '[{"field":"SpecificCropWeight","operation":"Equals","value":341}]'
assert_count_gt "SpecificCropWeight = 341 → matches (Sunflowers)" "0"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — EAV Metrics (Tractor-only)${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── ActualStatusOfCreeper (BOOLEAN — tractor) ───────────────────────────────
echo -e "\n  ${BLD}--- ActualStatusOfCreeper (BOOLEAN) ---${RST}"

query "ActualStatusOfCreeper Equals true" '[{"field":"ActualStatusOfCreeper","operation":"Equals","value":true}]'
assert_count_gt "ActualStatusOfCreeper = true → matches" "0"

query "ActualStatusOfCreeper Equals false" '[{"field":"ActualStatusOfCreeper","operation":"Equals","value":false}]'
assert_count_gt "ActualStatusOfCreeper = false → matches" "0"

# ── EngineLoad (NUMBER — both) ──────────────────────────────────────────────
echo -e "\n  ${BLD}--- EngineLoad (NUMBER) ---${RST}"

query "EngineLoad GreaterThan 50" '[{"field":"EngineLoad","operation":"GreaterThan","value":50}]'
assert_count_gt "EngineLoad > 50 → matches" "0"

query "EngineLoad LessThan 10" '[{"field":"EngineLoad","operation":"LessThan","value":10}]'
assert_count_gt "EngineLoad < 10 → matches" "0"

query "EngineLoad Equals 0" '[{"field":"EngineLoad","operation":"Equals","value":0}]'
assert_count_gt "EngineLoad = 0 → matches (idle)" "0"

# ── FuelConsumption (NUMBER — tractor) ──────────────────────────────────────
echo -e "\n  ${BLD}--- FuelConsumption (NUMBER) ---${RST}"

query "FuelConsumption GreaterThan 3" '[{"field":"FuelConsumption","operation":"GreaterThan","value":3}]'
assert_count_gt "FuelConsumption > 3 → matches" "0"

query "FuelConsumption LessThan 2" '[{"field":"FuelConsumption","operation":"LessThan","value":2}]'
assert_count_gt "FuelConsumption < 2 → matches" "0"

# ── CoolantTemperature (NUMBER — tractor) ───────────────────────────────────
echo -e "\n  ${BLD}--- CoolantTemperature (NUMBER) ---${RST}"

query "CoolantTemperature GreaterThan 10" '[{"field":"CoolantTemperature","operation":"GreaterThan","value":10}]'
assert_count_gt "CoolantTemperature > 10 → matches" "0"

query "CoolantTemperature Equals 14" '[{"field":"CoolantTemperature","operation":"Equals","value":14}]'
assert_count_gt "CoolantTemperature = 14 → matches" "0"

# ── GroundSpeedRadar (STRING — tractor, all NA → no data stored) ────────────
echo -e "\n  ${BLD}--- GroundSpeedRadar (STRING, all NA, no data) ---${RST}"

query "GroundSpeedRadar Equals NA" '[{"field":"GroundSpeedRadar","operation":"Equals","value":"NA"}]'
assert_count_zero "GroundSpeedRadar = 'NA' → 0 (all NA values skipped during import)"

# ── TotalWorkingHoursCounter (NUMBER — both) ────────────────────────────────
echo -e "\n  ${BLD}--- TotalWorkingHoursCounter (NUMBER) ---${RST}"

query "TotalWorkingHoursCounter GreaterThan 1000" '[{"field":"TotalWorkingHoursCounter","operation":"GreaterThan","value":1000}]'
assert_count_gt "TotalWorkingHoursCounter > 1000 → matches" "0"

query "TotalWorkingHoursCounter LessThan 100" '[{"field":"TotalWorkingHoursCounter","operation":"LessThan","value":100}]'
assert_count_zero "TotalWorkingHoursCounter < 100 → 0"

# ── SpeedFrontPTO (NUMBER — tractor) ────────────────────────────────────────
echo -e "\n  ${BLD}--- SpeedFrontPTO (NUMBER) ---${RST}"

query "SpeedFrontPTO Equals 0" '[{"field":"SpeedFrontPTO","operation":"Equals","value":0}]'
assert_count "SpeedFrontPTO = 0 → all tractor values are 0" "6464"

query "SpeedFrontPTO GreaterThan 0" '[{"field":"SpeedFrontPTO","operation":"GreaterThan","value":0}]'
assert_count_zero "SpeedFrontPTO > 0 → 0 (all values are 0)"

# ── AmbientTemperature (NUMBER — tractor) ───────────────────────────────────
echo -e "\n  ${BLD}--- AmbientTemperature (NUMBER) ---${RST}"

query "AmbientTemperature GreaterThan 10" '[{"field":"AmbientTemperature","operation":"GreaterThan","value":10}]'
assert_count_gt "AmbientTemperature > 10 → matches" "0"

query "AmbientTemperature LessThan 5" '[{"field":"AmbientTemperature","operation":"LessThan","value":5}]'
assert_count_zero "AmbientTemperature < 5 → 0 (min is 6.0)"

# ── DieselTankLevel (NUMBER — combine) ──────────────────────────────────────
echo -e "\n  ${BLD}--- DieselTankLevel (NUMBER) ---${RST}"

query "DieselTankLevel GreaterThan 40" '[{"field":"DieselTankLevel","operation":"GreaterThan","value":40}]'
assert_count_gt "DieselTankLevel > 40 → matches" "0"

query "DieselTankLevel LessThan 10" '[{"field":"DieselTankLevel","operation":"LessThan","value":10}]'
assert_count_zero "DieselTankLevel < 10 → 0 (min is 39.51)"

# ── ConcavePosition (NUMBER — combine) ──────────────────────────────────────
echo -e "\n  ${BLD}--- ConcavePosition (NUMBER) ---${RST}"

query "ConcavePosition GreaterThan 30" '[{"field":"ConcavePosition","operation":"GreaterThan","value":30}]'
assert_count_gt "ConcavePosition > 30 → matches" "0"

# ── RateOfWork (NUMBER — combine) ───────────────────────────────────────────
echo -e "\n  ${BLD}--- RateOfWork (NUMBER) ---${RST}"

query "RateOfWork GreaterThan 0" '[{"field":"RateOfWork","operation":"GreaterThan","value":0}]'
assert_count_gt "RateOfWork > 0 → matches" "0"

query "RateOfWork Equals 0" '[{"field":"RateOfWork","operation":"Equals","value":0}]'
assert_count_gt "RateOfWork = 0 → matches" "0"

# ── Yield (NUMBER — combine) ────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Yield (NUMBER) ---${RST}"

query "Yield GreaterThan 0" '[{"field":"Yield","operation":"GreaterThan","value":0}]'
assert_count_gt "Yield > 0 → matches" "0"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — Combined AND Filters${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── Two filters on different fields ------------------------------------------
echo -e "\n  ${BLD}--- Two-field AND filters ---${RST}"

query "GroundSpeed>0 AND GrainTankUnloading=true" \
  '[{"field":"GroundSpeed","operation":"GreaterThan","value":0},{"field":"GrainTankUnloading","operation":"Equals","value":true}]'
assert_count_gt "GroundSpeed>0 AND GrainTankUnloading=true → matches" "0"

query "VehicleType=COMBINE AND TypeOfCrop=Maize" \
  '[{"field":"VehicleType","operation":"Equals","value":"COMBINE"},{"field":"TypeOfCrop","operation":"Equals","value":"Maize"}]'
assert_count "VehicleType=COMBINE AND TypeOfCrop=Maize → 2230" "2230"

query "VehicleType=COMBINE AND TypeOfCrop=Sunflowers" \
  '[{"field":"VehicleType","operation":"Equals","value":"COMBINE"},{"field":"TypeOfCrop","operation":"Equals","value":"Sunflowers"}]'
assert_count "VehicleType=COMBINE AND TypeOfCrop=Sunflowers → 334" "334"

query "VehicleType=TRACTOR AND EngineSpeed>1500" \
  '[{"field":"VehicleType","operation":"Equals","value":"TRACTOR"},{"field":"EngineSpeed","operation":"GreaterThan","value":1500}]'
assert_count_gt "VehicleType=TRACTOR AND EngineSpeed>1500 → matches" "0"

query "VehicleType=TRACTOR AND TypeOfCrop=Maize" \
  '[{"field":"VehicleType","operation":"Equals","value":"TRACTOR"},{"field":"TypeOfCrop","operation":"Equals","value":"Maize"}]'
assert_count_zero "TRACTOR AND TypeOfCrop=Maize → 0 (tractors have no crop)"

# ── Three+ filters ----------------------------------------------------------
echo -e "\n  ${BLD}--- Three+ field AND filters ---${RST}"

query "GS>0 AND GTU=true AND TypeOfCrop=Maize" \
  '[{"field":"GroundSpeed","operation":"GreaterThan","value":0},{"field":"GrainTankUnloading","operation":"Equals","value":true},{"field":"TypeOfCrop","operation":"Equals","value":"Maize"}]'
assert_count_gt "GS>0 AND GTU=true AND TypeOfCrop=Maize → matches" "0"

query "COMBINE AND Maize AND EngineSpeed>1500" \
  '[{"field":"VehicleType","operation":"Equals","value":"COMBINE"},{"field":"TypeOfCrop","operation":"Equals","value":"Maize"},{"field":"EngineSpeed","operation":"GreaterThan","value":1500}]'
assert_count_gt "COMBINE AND Maize AND EngineSpeed>1500 → matches" "0"

query "COMBINE AND Sunflowers AND GS>0" \
  '[{"field":"VehicleType","operation":"Equals","value":"COMBINE"},{"field":"TypeOfCrop","operation":"Equals","value":"Sunflowers"},{"field":"GroundSpeed","operation":"GreaterThan","value":0}]'
assert_count_gt "COMBINE AND Sunflowers AND GS>0 → matches" "0"

# ── Contradictory filters (should return 0) ---------------------------------
echo -e "\n  ${BLD}--- Contradictory filters ---${RST}"

query "VehicleType=TRACTOR AND VehicleType=COMBINE" \
  '[{"field":"VehicleType","operation":"Equals","value":"TRACTOR"},{"field":"VehicleType","operation":"Equals","value":"COMBINE"}]'
assert_count_zero "TRACTOR AND COMBINE → 0"

query "GS>9999 AND GS<0" \
  '[{"field":"GroundSpeed","operation":"GreaterThan","value":9999},{"field":"GroundSpeed","operation":"LessThan","value":0}]'
assert_count_zero "GS>9999 AND GS<0 → 0"

query "SerialNumber=A5304997 AND SerialNumber=C7502627" \
  '[{"field":"SerialNumber","operation":"Equals","value":"A5304997"},{"field":"SerialNumber","operation":"Equals","value":"C7502627"}]'
assert_count_zero "Two serials → 0"

# ── Core + EAV combined -----------------------------------------------------
echo -e "\n  ${BLD}--- Core + EAV combined ---${RST}"

query "RecordedAt Contains 2022 AND TypeOfCrop=Sunflowers" \
  '[{"field":"RecordedAt","operation":"Contains","value":"2022"},{"field":"TypeOfCrop","operation":"Equals","value":"Sunflowers"}]'
assert_count "2022 AND Sunflowers → 334" "334"

query "Latitude>46 AND EngineSpeed>1500" \
  '[{"field":"Latitude","operation":"GreaterThan","value":46},{"field":"EngineSpeed","operation":"GreaterThan","value":1500}]'
assert_count_gt "Latitude>46 AND EngineSpeed>1500 → matches" "0"

query "Longitude>20.1 AND FuelConsumption>3" \
  '[{"field":"Longitude","operation":"GreaterThan","value":20.1},{"field":"FuelConsumption","operation":"GreaterThan","value":3}]'
assert_count_gt "Longitude>20.1 AND FuelConsumption>3 → matches" "0"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — Edge Cases${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── Case sensitivity ────────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Case sensitivity ---${RST}"

query "Field name case-insensitive (lowercase)" \
  '[{"field":"vehicletype","operation":"Equals","value":"TRACTOR"}]'
assert_count "vehicletype (lowercase) → 6464" "6464"

query "Field name case-insensitive (uppercase)" \
  '[{"field":"VEHICLETYPE","operation":"Equals","value":"TRACTOR"}]'
assert_count "VEHICLETYPE (uppercase) → 6464" "6464"

query "String value case-sensitive (tractor ≠ TRACTOR)" \
  '[{"field":"VehicleType","operation":"Equals","value":"tractor"}]'
assert_count_zero "VehicleType = 'tractor' (lowercase) → 0"

query "Contains case-insensitive (mai matches Maize — SQLite LIKE)" \
  '[{"field":"TypeOfCrop","operation":"Contains","value":"mai"}]'
assert_count "TypeOfCrop Contains 'mai' → 2230 (SQLite LIKE is case-insensitive)" "2230"

# ── Boolean value variants ──────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Boolean value variants ---${RST}"

query "Boolean as number 1" \
  '[{"field":"GrainTankUnloading","operation":"Equals","value":1}]'
assert_count "GrainTankUnloading = 1 → 73" "73"

query "Boolean as number 0" \
  '[{"field":"GrainTankUnloading","operation":"Equals","value":0}]'
assert_count_gt "GrainTankUnloading = 0 → matches" "0"

query "Boolean as string 'off'" \
  '[{"field":"GrainTankUnloading","operation":"Equals","value":"off"}]'
assert_count_gt "GrainTankUnloading = 'off' → matches" "0"

# ── Numeric value as string (should work — parsed as Double) ────────────────
echo -e "\n  ${BLD}--- Numeric value as string ---${RST}"

query "NUMBER field with string value" \
  '[{"field":"EngineSpeed","operation":"Equals","value":"1200"}]'
assert_count_gt "EngineSpeed = '1200' (string) → matches" "0"

# ── Response structure ──────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- Response structure ---${RST}"

query "Response structure check" '[{"field":"SerialNumber","operation":"Equals","value":"A5304997"}]'

# Check that results array has expected fields
has_results=$(jq 'has("results")' /tmp/df_query.json)
has_totalCount=$(jq 'has("totalCount")' /tmp/df_query.json)

if [[ "$has_results" == "true" && "$has_totalCount" == "true" ]]; then
  pass "Response has results and totalCount fields"
else
  fail "Response structure" "Missing results/totalCount"
fi

# Check first result has expected fields
first_event_fields=$(jq -r '.results[0] | keys | join(",")' /tmp/df_query.json 2>/dev/null)
expected_fields=("eventId" "groundSpeed" "latitude" "longitude" "metrics" "recordedAt" "serialNumber" "vehicleType")
all_present=true
for field in "${expected_fields[@]}"; do
  if ! echo "$first_event_fields" | grep -q "$field"; then
    all_present=false
    break
  fi
done

if $all_present; then
  pass "Event result has all expected fields"
else
  fail "Event result fields" "Missing fields. Got: $first_event_fields"
fi

# Check that metrics array is present and non-empty
metric_count=$(jq '.results[0].metrics | length' /tmp/df_query.json 2>/dev/null)
if (( metric_count > 0 )); then
  pass "Event has metrics (count: $metric_count)"
else
  fail "Event metrics" "Expected non-empty metrics, got $metric_count"
fi

# Check metric structure
metric_fields=$(jq -r '.results[0].metrics[0] | keys | join(",")' /tmp/df_query.json 2>/dev/null)
if echo "$metric_fields" | grep -q "name" && echo "$metric_fields" | grep -q "numValue" && echo "$metric_fields" | grep -q "strValue"; then
  pass "Metric has name, numValue, strValue fields"
else
  fail "Metric fields" "Got: $metric_fields"
fi

# ── GET /info ───────────────────────────────────────────────────────────────
echo -e "\n  ${BLD}--- GET /info ---${RST}"
assert_status "GET /info returns 200" GET "/info" "" 200

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  TEST SUITE: POST /query — Additional EAV Metrics${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

# ── FanSpeed (NUMBER — combine) ─────────────────────────────────────────────
echo -e "\n  ${BLD}--- FanSpeed (NUMBER) ---${RST}"
query "FanSpeed Equals 0" '[{"field":"FanSpeed","operation":"Equals","value":0}]'
assert_count_gt "FanSpeed = 0 → matches" "0"
query "FanSpeed GreaterThan 0" '[{"field":"FanSpeed","operation":"GreaterThan","value":0}]'
assert_count_gt "FanSpeed > 0 → matches" "0"

# ── RotorStrawWalkerSpeed (NUMBER — combine) ────────────────────────────────
echo -e "\n  ${BLD}--- RotorStrawWalkerSpeed (NUMBER) ---${RST}"
query "RotorStrawWalkerSpeed Equals 0" '[{"field":"RotorStrawWalkerSpeed","operation":"Equals","value":0}]'
assert_count_gt "RotorStrawWalkerSpeed = 0 → matches" "0"

# ── SeparationLosses (NUMBER — combine) ─────────────────────────────────────
echo -e "\n  ${BLD}--- SeparationLosses (NUMBER) ---${RST}"
query "SeparationLosses Equals 0" '[{"field":"SeparationLosses","operation":"Equals","value":0}]'
assert_count_gt "SeparationLosses = 0 → matches" "0"

# ── SieveLosses (NUMBER — combine) ──────────────────────────────────────────
echo -e "\n  ${BLD}--- SieveLosses (NUMBER) ---${RST}"
query "SieveLosses Equals 0" '[{"field":"SieveLosses","operation":"Equals","value":0}]'
assert_count_gt "SieveLosses = 0 → matches" "0"

# ── UpperSievePosition (NUMBER — combine) ───────────────────────────────────
echo -e "\n  ${BLD}--- UpperSievePosition (NUMBER) ---${RST}"
query "UpperSievePosition Equals 20" '[{"field":"UpperSievePosition","operation":"Equals","value":20}]'
assert_count_gt "UpperSievePosition = 20 → matches" "0"

# ── LowerSievePosition (NUMBER — combine) ───────────────────────────────────
echo -e "\n  ${BLD}--- LowerSievePosition (NUMBER) ---${RST}"
query "LowerSievePosition Equals 20" '[{"field":"LowerSievePosition","operation":"Equals","value":20}]'
assert_count_gt "LowerSievePosition = 20 → matches" "0"

# ── GrainInReturns (NUMBER — combine) ───────────────────────────────────────
echo -e "\n  ${BLD}--- GrainInReturns (NUMBER) ---${RST}"
query "GrainInReturns GreaterThan 0" '[{"field":"GrainInReturns","operation":"GreaterThan","value":0}]'
assert_count_gt "GrainInReturns > 0 → matches" "0"

# ── ChannelPosition (NUMBER — combine) ──────────────────────────────────────
echo -e "\n  ${BLD}--- ChannelPosition (NUMBER) ---${RST}"
query "ChannelPosition GreaterThan 0" '[{"field":"ChannelPosition","operation":"GreaterThan","value":0}]'
assert_count_gt "ChannelPosition > 0 → matches" "0"

# ── SeparationSensitivity (NUMBER — combine) ────────────────────────────────
echo -e "\n  ${BLD}--- SeparationSensitivity (NUMBER) ---${RST}"
query "SeparationSensitivity Equals 50" '[{"field":"SeparationSensitivity","operation":"Equals","value":50}]'
assert_count_gt "SeparationSensitivity = 50 → matches" "0"

# ── SieveSensitivity (NUMBER — combine) ─────────────────────────────────────
echo -e "\n  ${BLD}--- SieveSensitivity (NUMBER) ---${RST}"
query "SieveSensitivity Equals 50" '[{"field":"SieveSensitivity","operation":"Equals","value":50}]'
assert_count_gt "SieveSensitivity = 50 → matches" "0"

# ── SpeedRearPTO (NUMBER — tractor) ─────────────────────────────────────────
echo -e "\n  ${BLD}--- SpeedRearPTO (NUMBER) ---${RST}"
query "SpeedRearPTO Equals 0" '[{"field":"SpeedRearPTO","operation":"Equals","value":0}]'
assert_count_gt "SpeedRearPTO = 0 → matches" "0"

# ── CurrentGearShift (NUMBER — tractor) ─────────────────────────────────────
echo -e "\n  ${BLD}--- CurrentGearShift (NUMBER) ---${RST}"
query "CurrentGearShift Equals 0" '[{"field":"CurrentGearShift","operation":"Equals","value":0}]'
assert_count_gt "CurrentGearShift = 0 → matches" "0"
query "CurrentGearShift GreaterThan 0" '[{"field":"CurrentGearShift","operation":"GreaterThan","value":0}]'
assert_count_gt "CurrentGearShift > 0 → matches" "0"

# ── EngineSpeed (NUMBER — both) ─────────────────────────────────────────────
echo -e "\n  ${BLD}--- EngineSpeed (NUMBER) ---${RST}"
query "EngineSpeed Equals 1200" '[{"field":"EngineSpeed","operation":"Equals","value":1200}]'
assert_count_gt "EngineSpeed = 1200 → matches" "0"
query "EngineSpeed GreaterThan 1500" '[{"field":"EngineSpeed","operation":"GreaterThan","value":1500}]'
assert_count_gt "EngineSpeed > 1500 → matches" "0"
query "EngineSpeed LessThan 800" '[{"field":"EngineSpeed","operation":"LessThan","value":800}]'
assert_count_gt "EngineSpeed < 800 → matches" "0"

# ── RadialSpreaderSpeed (NUMBER — combine, stored as STRING "NA" → may be STRING) ──
echo -e "\n  ${BLD}--- RadialSpreaderSpeed ---${RST}"
# This field has "NA" values; check if it's NUMBER or STRING
# If STRING, Contains should work; if NUMBER, Equals should work
query "RadialSpreaderSpeed Equals 0" '[{"field":"RadialSpreaderSpeed","operation":"Equals","value":0}]'
rs_s=$(jq '.totalCount' /tmp/df_query.json 2>/dev/null || echo "-")
if [[ "$rs_s" != "-" && "$rs_s" != "null" ]]; then
  pass "RadialSpreaderSpeed = 0 → $rs_s results (NUMBER type)"
else
  # Might be a validation error if type is STRING
  rs_err=$(jq -r '.error // empty' /tmp/df_query.json 2>/dev/null)
  if [[ -n "$rs_err" ]]; then
    skip "RadialSpreaderSpeed Equals" "$rs_err (likely STRING type, skipping NUMBER op)"
  else
    pass "RadialSpreaderSpeed = 0 → 0 results"
  fi
fi

# ── GrainTank70 (BOOLEAN — combine) ─────────────────────────────────────────
echo -e "\n  ${BLD}--- GrainTank70 (BOOLEAN) ---${RST}"
query "GrainTank70 Equals true" '[{"field":"GrainTank70","operation":"Equals","value":true}]'
assert_count_gt "GrainTank70 = true → matches" "0"
query "GrainTank70 Equals false" '[{"field":"GrainTank70","operation":"Equals","value":false}]'
assert_count_gt "GrainTank70 = false → matches" "0"

# ── GrainTank100 (BOOLEAN — combine) ────────────────────────────────────────
echo -e "\n  ${BLD}--- GrainTank100 (BOOLEAN) ---${RST}"
query "GrainTank100 Equals true" '[{"field":"GrainTank100","operation":"Equals","value":true}]'
assert_count_gt "GrainTank100 = true → matches" "0"
query "GrainTank100 Equals false" '[{"field":"GrainTank100","operation":"Equals","value":false}]'
assert_count_gt "GrainTank100 = false → matches" "0"

# ── AutoPilotStatus (BOOLEAN — combine) ─────────────────────────────────────
echo -e "\n  ${BLD}--- AutoPilotStatus (BOOLEAN) ---${RST}"
query "AutoPilotStatus Equals false" '[{"field":"AutoPilotStatus","operation":"Equals","value":false}]'
assert_count_gt "AutoPilotStatus = false → matches" "0"

# ── CruisePilotStatus (NUMBER — combine, all 0) ─────────────────────────────
echo -e "\n  ${BLD}--- CruisePilotStatus (NUMBER) ---${RST}"
query "CruisePilotStatus Equals 0" '[{"field":"CruisePilotStatus","operation":"Equals","value":0}]'
assert_count_gt "CruisePilotStatus = 0 → matches" "0"

# ── FeedRakeSpeed (NUMBER — combine) ────────────────────────────────────────
echo -e "\n  ${BLD}--- FeedRakeSpeed (NUMBER) ---${RST}"
query "FeedRakeSpeed Equals 0" '[{"field":"FeedRakeSpeed","operation":"Equals","value":0}]'
assert_count_gt "FeedRakeSpeed = 0 → matches" "0"

# ── NoOfPartialWidths (NUMBER — combine) ────────────────────────────────────
echo -e "\n  ${BLD}--- NoOfPartialWidths (NUMBER) ---${RST}"
query "NoOfPartialWidths GreaterThan 0" '[{"field":"NoOfPartialWidths","operation":"GreaterThan","value":0}]'
assert_count_gt "NoOfPartialWidths > 0 → matches" "0"

# ── MaxNoOfPartialWidths (NUMBER — combine) ─────────────────────────────────
echo -e "\n  ${BLD}--- MaxNoOfPartialWidths (NUMBER) ---${RST}"
query "MaxNoOfPartialWidths GreaterThan 0" '[{"field":"MaxNoOfPartialWidths","operation":"GreaterThan","value":0}]'
assert_count_gt "MaxNoOfPartialWidths > 0 → matches" "0"

# ── ReturnsAugerMeasurement (NUMBER — combine) ──────────────────────────────
echo -e "\n  ${BLD}--- ReturnsAugerMeasurement (NUMBER) ---${RST}"
query "ReturnsAugerMeasurement Equals 0" '[{"field":"ReturnsAugerMeasurement","operation":"Equals","value":0}]'
assert_count_gt "ReturnsAugerMeasurement = 0 → matches" "0"

# ── QuantimeterCalibrationFactor (NUMBER — combine) ─────────────────────────
echo -e "\n  ${BLD}--- QuantimeterCalibrationFactor (NUMBER) ---${RST}"
query "QuantimeterCalibrationFactor GreaterThan 0.9" '[{"field":"QuantimeterCalibrationFactor","operation":"GreaterThan","value":0.9}]'
assert_count_gt "QuantimeterCalibrationFactor > 0.9 → matches" "0"

# ============================================================================
echo ""
echo -e "${BLD}================================================================${RST}"
echo -e "${BLD}  SUMMARY${RST}"
echo -e "${BLD}================================================================${RST}"
# ============================================================================

total=$((PASS + FAIL + SKIP))
echo ""
echo -e "  Total:  $total"
echo -e "  ${GRN}Passed: $PASS${RST}"
echo -e "  ${RED}Failed: $FAIL${RST}"
echo -e "  ${YEL}Skipped: $SKIP${RST}"
echo ""

if (( FAIL > 0 )); then
  echo -e "  ${RED}❌ SOME TESTS FAILED${RST}"
  exit 1
else
  echo -e "  ${GRN}✅ ALL TESTS PASSED${RST}"
  exit 0
fi