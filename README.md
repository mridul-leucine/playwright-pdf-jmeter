# Playwright PDF JMeter Load Test

Load-test local Playwright PDF rendering via JMeter.

## Architecture

```
JMeter (threads) → GET http://localhost:8080/pdf?jobId=XXX → HTTP Server → Playwright renders PDF → Returns PDF bytes
```

## Setup

1. Clone and build:
   ```bash
   git clone https://github.com/mridul-leucine/playwright-pdf-jmeter.git
   cd playwright-pdf-jmeter
   ```

2. Add your token in `config.json`:
   ```json
   { "token": "Bearer eyJ..." }
   ```

3. Install Playwright browsers (first time only):
   ```bash
   ./gradlew run --args="--login"
   ```

## Run

### Option 1: Self-contained JMeter test (recommended)
Open `jmeter/pdf-local-test.jmx` in JMeter GUI and click Play. The test plan auto-starts and stops the server.

### Option 2: Manual server + JMeter

**Terminal 1 — Start server:**
```bash
./gradlew run --args="--server"
```

**Terminal 2 — Run JMeter CLI:**
```bash
jmeter -n -t jmeter/pdf-local-test.jmx -l jmeter/results.jtl -e -o jmeter/report/
```

### Option 3: Manual test
```bash
./gradlew run --args="--server"
# In another terminal or browser:
curl http://localhost:8080/pdf?jobId=722492974613180416 -o test.pdf
```

## CLI Flags

| Flag | Description |
|------|-------------|
| `--server` | Start HTTP server for JMeter |
| `--port=N` | Server port (default 8080) |
| `--local` | Render single PDF locally |
| `--benchmark --n=100` | Benchmark N renders |
| `--login` | Browser login to get token |

## JMeter Test Plan

- **5 threads, 2 loops** = 10 PDF requests
- CSV-driven job IDs from `jmeter/job-ids.csv`
- Assertions: HTTP 200 + Content-Type application/pdf
- Listeners: Summary Report, Aggregate Report, View Results Tree
