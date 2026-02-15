# Playwright PDF + JMeter Load Test

Renders job report PDFs locally via Playwright and exposes an HTTP endpoint for JMeter load testing.

## Setup

1. Add your token in `config.json`:
   ```json
   { "token": "Bearer eyJ..." }
   ```
   Or run `./gradlew run --args="--login"` to login via browser.

2. Install Playwright browsers (first time): `./gradlew run --args="--login"`

## Usage

**Start HTTP server (for JMeter):**
```bash
./gradlew run --args="--server"
```

**Render a single PDF:**
```bash
./gradlew run --args="--jobId=722492974613180416"
```

**JMeter test:**
Open `jmeter/pdf-local-test.jmx` in JMeter and run.

## Flags

| Flag | Description |
|------|-------------|
| `--server` | Start HTTP server on port 8080 |
| `--port=N` | Custom server port |
| `--login` | Browser login to get token |
| `--jobId=ID` | Render specific job |
