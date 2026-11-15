# Hwaruruk 🔥 — Backend API

**Live API:** `https://burnline-api.unzipp.cloud/api/v1/{region}/…` · try [`/KOR/meta`](https://burnline-api.unzipp.cloud/api/v1/KOR/meta)
**Web app:** [frontend link] · **Data pipeline:** [pipeline repo link]

NASA Space Apps Challenge 2026 · #9 Harmonization of MODIS and VIIRS Hot Spots · Team JJYa (Seoul)

## What it does

Satellite fire records break in 2012, when MODIS hands over to VIIRS: joined as-is, the same fires look 2–3× more frequent. Our data pipeline harmonizes 25 years of NASA FIRMS hotspots onto a single NOAA-20 scale. This backend serves that harmonized record so the web app can answer one question for any area: **is this month’s fire activity normal, or unusual?**

**Implemented**

- **Six regions on five continents:** South Korea (`KOR`), California (`CAL`), British Columbia (`BC`), Greece (`GRC`), Chile (`CHL`), New South Wales (`NSW`). 1,114 areas in total, monthly from 2001-01.
- **Monthly series** for every country, province and district, with the raw (uncorrected) value alongside the harmonized one.
- **“vs. normal” judgement** (`record` · `above` · `normal` · `below`) against the 2001–2025 baseline. Sparse districts borrow their province’s judgement instead of reporting noise.
- **Map, detection clusters and official fire records** for each month, plus a monthly insight page that combines all of them.
- **Validation numbers** (2012 break test, leave-one-year-out error, detection rate by fire size) exposed for the methodology page.

## Try it

```bash
curl "https://burnline-api.unzipp.cloud/api/v1/KOR/series/KOR?from=2025-03&to=2025-03"
```

```json
{"id":"KOR","level":"nat","months":[{"month":"2025-03","value":1093.0,"raw_before":1093.0,"source":"NOAA-20",
 "quality":"high","estimated":false,"status":"final","rank":1,"n":25,"ratio_to_median":6.28}]}
```

March 2025 was South Korea’s highest March in 25 years: 1,093 fire cells, 6.28× the usual month.

```bash
curl "https://burnline-api.unzipp.cloud/api/v1/KOR/top?month=2025-03"                                   # top 3 districts
curl "https://burnline-api.unzipp.cloud/api/v1/KOR/insight/91817680B75376031789242?month=2025-03"       # Uiseong wildfire, all 9 cards
curl "https://burnline-api.unzipp.cloud/api/v1/CAL/judgement/52423323B88547646561946?month=2018-11"     # Butte County, Camp Fire
```

[Screenshot or GIF of the web app]

## Architecture

```
Browser ── Web app ── /api/* ──▶ Cloudflare Tunnel (burnline-api.unzipp.cloud)
                                   │  routed by path, Railway private network
          ┌────────────────────────┼─────────────────────────┬──────────────────────┐
     catalog-service        timeseries-service         spatial-service        insight-service
     meta, regions, geo     series, judgement          map, events,           insight, top, landing
                            baseline, export.csv       official fires         (calls the other three)
          └──────────── PostgreSQL (one schema per service, Flyway) ──────────┘
```

Heavy computation runs once, offline, in the data pipeline. On first start each service loads the pipeline’s JSON/CSV output into its own schema; requests then only read precomputed rows. Responses are cached by data version (ETag), with short cache times for months still in progress.

| Service | Port | Endpoints under `/api/v1/{region}` |
|---|---|---|
| `catalog-service` | 8081 | `/meta` · `/regions?level=&q=` · `/regions/{id}` · `/geo?level=&parent=` |
| `timeseries-service` | 8082 | `/series/{id}?from=&to=` · `/baseline/{id}` · `/judgement/{id}?month=` · `/judgement?month=` · `/export.csv?id=` |
| `spatial-service` | 8083 | `/map?month=&parent=` · `/events?region=&from=&to=` · `/official-fires?region=&month=` |
| `insight-service` | 8084 | `/insight/{id}?month=` · `/top?month=&parent=` · `/landing` |
| `common` | — | errors, codes, validation, cache, ETag, CORS, data loading, inter-service client (retry and circuit breaker) |

Every service also exposes `/health/live`, `/health/ready` and `/metrics`. Errors are `{"code", "message"}` with an HTTP status (`REGION_NOT_FOUND`, `MONTH_OUT_OF_RANGE`, `INVALID_PARAM`, `COUNTRY_NOT_READY`).

**Stack:** Java 21 · Spring Boot 3.5 · Gradle multi-module · PostgreSQL 16 · JdbcTemplate · Flyway · Redis (optional) · Railway · Cloudflare Tunnel

## Run locally

**Requirements:** Java 21, Docker (for PostgreSQL), and the data package.

1. **Get the data package.** `burnline_backend_data/` holds the pipeline output for all six regions (`output/`, `boundaries/`, `external/`). Download it from [data package link], or build it with the pipeline repo. The services read it once at start-up.

2. **Start PostgreSQL.**

```bash
docker run -d --name burnline-db -p 5432:5432 -e POSTGRES_DB=burnline -e POSTGRES_USER=burnline -e POSTGRES_PASSWORD=burnline postgres:16
```

3. **Build and run the four services.** Flyway creates the tables and the data is loaded on first start.

```bash
./gradlew dist
```

```bash
export DATA_DIR=/path/to/burnline_backend_data
for s in catalog timeseries spatial insight; do java -jar dist/$s-service.jar & done
```

```bash
curl "localhost:8082/api/v1/KOR/series/KOR?from=2025-03&to=2025-03"
```

On Windows (PowerShell), set `$env:DATA_DIR="C:\path\to\burnline_backend_data"` and start each jar with `Start-Process java -ArgumentList '-jar','dist\catalog-service.jar'`.

4. **Run the tests.** They start an embedded PostgreSQL (no Docker needed), load the real data package and check the responses against the API spec examples.

```bash
DATA_DIR=/path/to/burnline_backend_data ./gradlew test
```

5. **Check a running stack against the API spec (optional).** With all four services up, `api-test/spec_test.py` calls every endpoint and writes `api-test/report.md`.

```bash
python3 api-test/spec_test.py --sweep
```

Add `--base https://burnline-api.unzipp.cloud` to run the same checks against the live API.

### Environment variables

All have local defaults, so none are required to run on one machine. No secrets are stored in this repository.

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/burnline` | PostgreSQL connection |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `burnline` / `burnline` | Local development only; set your own in deployment |
| `DATA_DIR` | `../../burnline_backend_data/burnline_backend_data` | Data package location |
| `DATA_LOAD` | `if-empty` | `if-empty`, `always` (reload after a new data package) or `never` |
| `CACHE_URL` | `redis://localhost:6379` | Optional; without Redis only caching is off |
| `CORS_ORIGINS` | `http://localhost:*` | Allowed web origins |
| `CATALOG_URL` / `TIMESERIES_URL` / `SPATIAL_URL` | `http://localhost:8081` / `8082` / `8083` | Used by insight-service |
| `SERVER_PORT` | 8081 / 8082 / 8083 / 8084 | Per service |
| `TUNNEL_CREDS` | — | Deployment only: Cloudflare Tunnel credentials for the `deploy/railway/cloudflared` container |

## Deployment

The live API runs on Railway: one container per service (`deploy/railway/<service>/Dockerfile`, built from `./gradlew dist`) and one Cloudflare Tunnel container that routes each path to its service over Railway’s private network (`deploy/railway/cloudflared/config.yml`). Tunnel credentials come from the `TUNNEL_CREDS` variable and are never committed. An alternative Fly.io + Neon setup is described in `deploy/DEPLOY_FLYIO.md`.

## Limitations and unfinished work

- **Data snapshot:** FIRMS data through 2026-11-14. Refreshing means rerunning the pipeline and reloading (`DATA_LOAD=always`); daily automatic NRT updates are not built yet.
- **What the numbers mean:** fire cells measure fire activity (0.01° cell × local day), not burned area or fire counts, and include prescribed and agricultural burns.
- **Before 2012:** values are estimates converted from MODIS. Districts are judged from 2012 onward.
- **External validation:** BC and Greece are marked inconclusive because their official records changed definition over time.
- **Boundaries:** South Korean provinces use geoBoundaries, which predates Gunwi County joining Daegu in 2023.

## Data sources

NASA FIRMS active fire (MODIS C6.1; VIIRS C2 from S-NPP, NOAA-20 and NOAA-21) · official fire records from the Korea Forest Service and KOSIS, CAL FIRE FRAP, BC Wildfire Service, the Hellenic Fire Service, CONAF and NSW NPWS · boundaries from geoBoundaries (CC BY 3.0/4.0) and the BC Data Catalogue (OGL–BC).

AI tools: Claude was used for writing and reviewing code and documentation.
