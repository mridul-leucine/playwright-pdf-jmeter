# Playwright PDF Renderer + JMeter Load Test

A thin Playwright-based PDF rendering server. JMeter owns all API interaction (fetching job data, authentication); the Java server is a pure PDF renderer that accepts job JSON via POST.

## Architecture

```
JMeter (with token/headers)
  → GET Streem API /jobs/{id}    (fetch job JSON)
  → POST localhost:8080/pdf      (send JSON body + auth headers)
  → Java renders PDF via Playwright
  → returns PDF bytes
```

## Setup

1. Install Playwright browsers (first time):
   ```bash
   ./gradlew run --args="--server"
   ```

2. Add job IDs to `jmeter/job-ids.csv` (one per line).

3. Update the `token` variable in the JMeter test plan (`jmeter/pdf-local-test.jmx`).

## Usage

**Start the PDF server:**
```bash
./gradlew run --args="--server"
./gradlew run --args="--server --port=9090"
```

**Manual test with curl:**
```bash
curl -X POST http://localhost:8080/pdf \
  -H "Authorization: Bearer eyJ..." \
  -H "facilityId: 1616367803" \
  -H "Content-Type: application/json" \
  -d @job-response.json \
  -o report.pdf
```

**JMeter load test:**
Open `jmeter/pdf-local-test.jmx` in JMeter and run.

## Server API

### `POST /pdf`

**Headers (required):**
| Header | Description |
|--------|-------------|
| `Authorization` | Bearer token for image downloads |
| `facilityId` | Facility ID for image downloads |
| `Content-Type` | `application/json` |

**Body:** Raw JSON — either the full API response (`{"data": {...}}`) or the job object directly.

**Response:** `application/pdf` bytes.

## Flags

| Flag | Description |
|------|-------------|
| `--server` | Start HTTP server (required) |
| `--port=N` | Custom server port (default: 8080) |
