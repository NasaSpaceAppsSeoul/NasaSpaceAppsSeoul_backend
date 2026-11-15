# Fly.io + Neon 배포 가이드 (완전 무료)

> 로컬 서버 탈출 목표: Spring Boot 4개 서비스 + PostgreSQL → Fly.io 3 VM + Neon 서버리스 DB

---

## 최종 구조

```
Vercel → burnline-api.unzipp.cloud (Cloudflare Tunnel 또는 Workers)
                    ↓
   ┌────────────────┬──────────────────┬──────────────────┐
   │ Fly.io VM 1    │ Fly.io VM 2      │ Fly.io VM 3      │
   │ catalog :8081  │ timeseries :8082 │ spatial  :8083   │
   │ insight  :8084 │                  │                  │
   │ (256MB)        │ (256MB)          │ (256MB)          │
   └────────────────┴──────────────────┴──────────────────┘
                    ↓
         Neon Serverless PostgreSQL (무료 0.5GB)
```

### 무료 한도

| 항목 | 무료 한도 |
|---|---|
| Fly.io VM | shared-cpu-1x **256MB RAM** × 3개 |
| Neon DB | **0.5GB** PostgreSQL, 자동 중단 |
| Cloudflare Tunnel / Workers | 무료 |
| Vercel 프론트 | 무료 |
| **합계** | **$0** |

### 4 → 3 서비스로 줄이는 이유

Fly.io 무료 VM이 3개이므로 **catalog + insight를 하나의 컨테이너**에 묶는다.  
insight-service는 DB가 없고 다른 서비스를 호출만 하므로 함께 묶어도 무관하다.

---

## 단계별 체크리스트

```
□ 0단계  로컬 DB 사이즈 확인
□ 1단계  Neon DB 생성 & 로컬 DB 이전
□ 2단계  Dockerfile 3개 + start.sh 작성
□ 3단계  flyctl 설치 & 3개 앱 배포
□ 4단계  cloudflared config.yml 수정
□ 5단계  (선택) Cloudflare Workers로 로컬 완전 탈출
```

---

## 0단계 — 로컬 DB 사이즈 확인

```sql
-- 로컬 psql에서 실행
SELECT pg_size_pretty(pg_database_size('burnline'));

SELECT schemaname, tablename,
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname IN ('catalog','timeseries','spatial')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

| 결과 | 조치 |
|---|---|
| **0.5GB 이하** | Neon 무료 티어 사용 가능 |
| **0.5GB 초과** | Neon 대신 **CockroachDB Serverless (5GB 무료)** 사용 |

---

## 1단계 — Neon DB 세팅

### 가입

```
1. neon.tech 접속
2. GitHub 계정으로 가입 (신용카드 불필요)
3. New Project 생성
4. Connection string 복사
   예: postgresql://burnline:xxxx@ep-xxx.us-east-2.aws.neon.tech/burnline?sslmode=require
```

### 로컬 DB → Neon으로 이전

```bash
# 로컬 PC에서 dump
pg_dump -h localhost -U burnline burnline > burnline_dump.sql

# Neon으로 직접 restore
psql "postgresql://burnline:xxxx@ep-xxx.neon.tech/burnline?sslmode=require" \
  < burnline_dump.sql
```

---

## 2단계 — 빌드 & Dockerfile 작성

### jar 빌드

```bash
cd backend
./gradlew dist
# dist/ 에 생성:
#   catalog-service.jar
#   timeseries-service.jar
#   spatial-service.jar
#   insight-service.jar
```

### 디렉터리 구조

```
backend/deploy/fly/
├── catalog-insight/
│   ├── Dockerfile
│   └── start.sh
├── timeseries/
│   └── Dockerfile
└── spatial/
    └── Dockerfile
```

---

### VM1: catalog + insight

**`deploy/fly/catalog-insight/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/catalog-service.jar  catalog.jar
COPY dist/insight-service.jar  insight.jar
COPY deploy/fly/catalog-insight/start.sh start.sh
RUN chmod +x start.sh
EXPOSE 8081 8084
ENTRYPOINT ["/bin/bash", "/app/start.sh"]
```

**`deploy/fly/catalog-insight/start.sh`**

```bash
#!/bin/bash
set -e

JVM_OPTS="-Xmx90m -Xms16m -XX:+UseSerialGC \
  -XX:MaxMetaspaceSize=60m -XX:ReservedCodeCacheSize=24m \
  -Xss256k -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -Dspring.main.lazy-initialization=true"

# catalog 먼저 기동
java $JVM_OPTS \
  -Dserver.port=8081 \
  -jar /app/catalog.jar &

# catalog 기동 완료 후 insight 시작 (동시 기동 시 OOM 방지)
sleep 25

java $JVM_OPTS \
  -Dserver.port=8084 \
  -jar /app/insight.jar &

# 하나라도 종료되면 컨테이너 중단
wait -n
```

---

### VM2: timeseries

**`deploy/fly/timeseries/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/timeseries-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", \
  "-Xmx200m", "-Xms16m", "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=24m", \
  "-Xss256k", "-Dspring.main.lazy-initialization=true", \
  "-jar", "/app/app.jar"]
```

---

### VM3: spatial

**`deploy/fly/spatial/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY dist/spatial-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", \
  "-Xmx200m", "-Xms16m", "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=24m", \
  "-Xss256k", "-Dspring.main.lazy-initialization=true", \
  "-jar", "/app/app.jar"]
```

---

## 3단계 — Fly.io 배포

### flyctl 설치 & 가입

```bash
# Windows
iwr https://fly.io/install.ps1 -useb | iex

# GitHub 계정으로 가입 (신용카드 불필요)
flyctl auth signup
```

---

### VM1 배포: catalog + insight

```bash
cd backend

flyctl launch \
  --name burnline-catalog-insight \
  --dockerfile deploy/fly/catalog-insight/Dockerfile \
  --no-deploy \
  --region nrt
```

생성된 `fly.toml` 내용 확인 후 아래처럼 수정:

```toml
app = "burnline-catalog-insight"
primary_region = "nrt"

[build]
  dockerfile = "deploy/fly/catalog-insight/Dockerfile"

[env]
  DATA_LOAD          = "never"
  CORS_ORIGINS       = "https://your-app.vercel.app"
  TIMESERIES_URL     = "https://burnline-timeseries.fly.dev"
  SPATIAL_URL        = "https://burnline-spatial.fly.dev"

[[services]]
  internal_port = 8081
  protocol      = "tcp"
  [[services.ports]]
    port     = 443
    handlers = ["tls", "http"]

[[services]]
  internal_port = 8084
  protocol      = "tcp"
  [[services.ports]]
    port     = 8084
    handlers = ["http"]

[vm]
  memory   = "256mb"
  cpu_kind = "shared"
  cpus     = 1
```

```bash
# DB 접속 정보는 secrets로 (평문 노출 방지)
flyctl secrets set \
  DATABASE_URL="jdbc:postgresql://ep-xxx.neon.tech/burnline?sslmode=require" \
  DATABASE_USER="burnline" \
  DATABASE_PASSWORD="xxxx" \
  --app burnline-catalog-insight

flyctl deploy --app burnline-catalog-insight
```

---

### VM2 배포: timeseries

```bash
flyctl launch \
  --name burnline-timeseries \
  --dockerfile deploy/fly/timeseries/Dockerfile \
  --no-deploy \
  --region nrt

flyctl secrets set \
  DATABASE_URL="jdbc:postgresql://ep-xxx.neon.tech/burnline?sslmode=require" \
  DATABASE_USER="burnline" \
  DATABASE_PASSWORD="xxxx" \
  DATA_LOAD="never" \
  CORS_ORIGINS="https://your-app.vercel.app" \
  --app burnline-timeseries

flyctl deploy --app burnline-timeseries
```

---

### VM3 배포: spatial

```bash
flyctl launch \
  --name burnline-spatial \
  --dockerfile deploy/fly/spatial/Dockerfile \
  --no-deploy \
  --region nrt

flyctl secrets set \
  DATABASE_URL="jdbc:postgresql://ep-xxx.neon.tech/burnline?sslmode=require" \
  DATABASE_USER="burnline" \
  DATABASE_PASSWORD="xxxx" \
  DATA_LOAD="never" \
  CORS_ORIGINS="https://your-app.vercel.app" \
  TIMESERIES_URL="https://burnline-timeseries.fly.dev" \
  --app burnline-spatial

flyctl deploy --app burnline-spatial
```

---

### 배포 상태 확인

```bash
flyctl status --app burnline-catalog-insight
flyctl status --app burnline-timeseries
flyctl status --app burnline-spatial

# 로그 확인
flyctl logs --app burnline-catalog-insight
```

---

## 4단계 — cloudflared 설정 수정

기존 `~/.cloudflared/config.yml`의 `service` 주소를 로컬 포트 → Fly.io URL로 교체:

```yaml
tunnel: <TUNNEL_ID>
credentials-file: C:\Users\<사용자>\.cloudflared\<TUNNEL_ID>.json

ingress:
  # VM1: catalog + insight
  - hostname: burnline-api.unzipp.cloud
    path: ^/api/v1/[^/]+/(meta|regions|geo)(/.*)?$
    service: https://burnline-catalog-insight.fly.dev

  - hostname: burnline-api.unzipp.cloud
    path: ^/api/v1/[^/]+/(insight|top|landing)(/.*)?$
    service: https://burnline-catalog-insight.fly.dev

  # VM2: timeseries
  - hostname: burnline-api.unzipp.cloud
    path: ^/api/v1/[^/]+/(series|baseline|judgement|export\.csv)(/.*)?$
    service: https://burnline-timeseries.fly.dev

  # VM3: spatial
  - hostname: burnline-api.unzipp.cloud
    path: ^/api/v1/[^/]+/(map|events|official-fires)(/.*)?$
    service: https://burnline-spatial.fly.dev

  - service: http_status:404
```

```bash
# cloudflared 재시작
cloudflared tunnel --config %USERPROFILE%\.cloudflared\config.yml run burnline
```

> 이 단계까지 완료하면 로컬 PC를 꺼도 API가 동작한다.  
> 단, cloudflared 프로세스는 아직 로컬에서 실행 중이다.

---

## 5단계 — Cloudflare Workers로 로컬 완전 탈출 (선택)

cloudflared 프로세스마저 없애고 싶다면 **Cloudflare Workers**로 경로 라우팅을 대체한다.

### Workers 생성

```
Cloudflare 대시보드 → Workers & Pages → Create Worker
```

```js
// worker.js
export default {
  async fetch(request) {
    const url  = new URL(request.url);
    const path = url.pathname;

    let target;
    if (/\/api\/v1\/[^/]+\/(meta|regions|geo|insight|top|landing)/.test(path))
      target = "https://burnline-catalog-insight.fly.dev";
    else if (/\/api\/v1\/[^/]+\/(series|baseline|judgement|export\.csv)/.test(path))
      target = "https://burnline-timeseries.fly.dev";
    else if (/\/api\/v1\/[^/]+\/(map|events|official-fires)/.test(path))
      target = "https://burnline-spatial.fly.dev";
    else
      return new Response("Not Found", { status: 404 });

    const newUrl = target + path + url.search;
    return fetch(newUrl, {
      method:  request.method,
      headers: request.headers,
      body:    request.body,
    });
  }
}
```

### DNS 연결

```
Cloudflare 대시보드 → Workers → Triggers → Add Route
  Route: burnline-api.unzipp.cloud/api/*
  Zone:  unzipp.cloud
```

> 이후 cloudflared 프로세스 중단 가능 → 로컬 PC 완전 불필요

---

## 트러블슈팅

### OOM (Out of Memory) 발생 시

```bash
# 로그 확인
flyctl logs --app burnline-catalog-insight

# catalog-insight 메모리 증설 (256MB → 512MB, 유료 $3.19/월)
flyctl scale memory 512 --app burnline-catalog-insight
```

### Neon 콜드 스타트 (첫 요청 느림)

Neon이 5분 비활성 후 자동 중단됨. 첫 요청이 1-2초 느릴 수 있음.  
해결: Neon 대시보드 → Compute → Auto-suspend 비활성화 (Pro 플랜)

### 배포 후 서비스 간 통신 오류

`TIMESERIES_URL`, `SPATIAL_URL` 환경변수가 `fly.dev` 주소를 가리키는지 확인:

```bash
flyctl secrets list --app burnline-catalog-insight
```

---

## 환경변수 전체 목록

| 변수 | 값 | 적용 서비스 |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://ep-xxx.neon.tech/burnline?sslmode=require` | catalog-insight, timeseries, spatial |
| `DATABASE_USER` | `burnline` | 동일 |
| `DATABASE_PASSWORD` | Neon 발급값 | 동일 |
| `DATA_LOAD` | `never` | 동일 |
| `CORS_ORIGINS` | `https://your-app.vercel.app` | 동일 |
| `TIMESERIES_URL` | `https://burnline-timeseries.fly.dev` | catalog-insight, spatial |
| `SPATIAL_URL` | `https://burnline-spatial.fly.dev` | catalog-insight |

---

## 비용 정리

| 서비스 | 플랜 | 비용 |
|---|---|---|
| Fly.io VM × 3 | shared-cpu-1x 256MB (Always Free) | **$0** |
| Neon PostgreSQL | Free (0.5GB) | **$0** |
| Cloudflare Tunnel / Workers | Free | **$0** |
| Vercel 프론트 | Free | **$0** |
| **합계** | | **$0/월** |
