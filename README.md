# Burnline 백엔드 — A 파트 (시계열·판정) · B 파트 (공간·사건·화면)

Spring Boot 3.5 · Java 21 · Gradle 멀티 모듈 · PostgreSQL 16 · JdbcTemplate · Flyway

- 계약: `../API_명세_항목_v1.0.pdf`
- 역할: `../백엔드_역할_분담.pdf` (A = 시계열·판정)
- 원천 데이터: `../burnline_backend_data/burnline_backend_data/` (파이프라인 출력, 읽기 전용)

## 모듈

| 모듈 | 포트 | 엔드포인트 (`/api/v1/{KOR\|CAL\|BC}` 아래) | 스키마 |
|---|---|---|---|
| `catalog-service` | 8081 | `/meta` `/regions` `/regions/{id}` `/geo` · 내부 `/internal/regions/{country}/{id}` | `catalog` |
| `timeseries-service` | 8082 | `/series/{id}` `/baseline/{id}` `/judgement/{id}` `/export.csv` · 내부 `/internal/judge` | `timeseries` |
| `spatial-service` (B) | 8083 | `/map` `/events` `/official-fires` · 내부 `/internal/month-detail/{country}/{id}` `/internal/top/{country}` `/internal/landing/{country}` | `spatial` |
| `insight-service` (B) | 8084 | `/insight/{id}` `/top` `/landing` (DB 없음, catalog·timeseries·spatial 조합) | — |
| `common` | — | 에러 형식, 코드값, 검증, 요청 ID, Redis 캐시, ETag, CORS, 원천 읽기·적재 실행, 내부 호출(재시도·서킷) | — |

공통: `/health/live`, `/health/ready`, `/metrics`

## 실행

### 1. DB

A 파트는 PostGIS가 필요 없다 (경계는 GeoJSON을 jsonb로 저장).

**이 PC (설치돼 있음)**: PostgreSQL 16.10을 관리자 권한 없이 `%LOCALAPPDATA%\burnline-pg\`에 풀어 두었다.
계정·DB는 `burnline` / `burnline`, localhost:5432에서만 받는다.

```bash
PG="$LOCALAPPDATA/burnline-pg"
"$PG/pgsql/bin/pg_ctl.exe" -D "$PG/data" -l "$PG/server.log" -o "-p 5432 -c listen_addresses=localhost" start   # 켜기
"$PG/pgsql/bin/pg_ctl.exe" -D "$PG/data" stop                                                                   # 끄기
PGPASSWORD=burnline "$PG/pgsql/bin/psql.exe" -h localhost -U burnline -d burnline                              # 접속
```

재부팅하면 꺼져 있으니 위 "켜기"를 다시 실행한다. 지우려면 폴더째 삭제.

**다른 PC (Docker)**:

```bash
docker run -d --name burnline-db -p 5432:5432 \
  -e POSTGRES_DB=burnline -e POSTGRES_USER=burnline -e POSTGRES_PASSWORD=burnline \
  -v burnline-pg:/var/lib/postgresql/data postgres:16
```

### 2. 서비스

```bash
./gradlew :catalog-service:bootRun       # :8081
./gradlew :timeseries-service:bootRun    # :8082
```

처음 뜰 때 Flyway가 표를 만들고, 비어 있는 나라를 원천 파일에서 적재한다 (세 나라 합쳐 2초 안쪽).
원천 파일을 새로 받았으면 다시 적재:

```bash
./gradlew :timeseries-service:bootRun --args='--spring.profiles.active=load'   # 적재하고 종료
# 또는 실행 중 설정: DATA_LOAD=always
```

| 환경변수 | 기본값 |
|---|---|
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/burnline` / `burnline` / `burnline` |
| `DATA_DIR` | `../../burnline_backend_data/burnline_backend_data` (모듈 폴더 기준) |
| `DATA_LOAD` | `if-empty` (`never` / `always`) |
| `CACHE_URL` | `redis://localhost:6379` — 없으면 캐시만 꺼지고 동작 |
| `CORS_ORIGINS` | `http://localhost:*` |
| `SERVER_PORT` | 8081 / 8082 |

### 3. 테스트

```bash
./gradlew test
```

Docker 없이 임베디드 PostgreSQL을 띄워 실제 원천 파일을 적재하고 명세 7장 예시와 대조한다 (17개).

> Windows에서 프로젝트 경로에 한글이 있으면(`백엔드`) Gradle 테스트가 클래스를 못 찾는 문제가 있어,
> 그럴 때는 빌드 출력이 `%TEMP%\burnline-build\`로 간다. 실행용 jar는 `./gradlew dist` → `dist/*.jar`.

## 배포 (도메인)

Vercel은 Java 서버를 돌리지 못한다 → 프론트는 Vercel, 백엔드는 내 PC + Cloudflare Tunnel.

```
브라우저 ─▶ Vercel (프론트)  /api/* rewrite ─▶ api.<도메인> (Cloudflare Tunnel)
                                              └─ cloudflared 경로별 ─▶ :8081 catalog / :8082 timeseries / B 서비스
```

- cloudflared: 이 PC에는 `%LOCALAPPDATA%\burnline-cloudflared\cloudflared.exe`로 설치돼 있다 (관리자 권한 없이, PATH에는 없음).
- 이 PC의 터널: 이름 `burnline`, 주소 **`https://burnline-api.unzipp.cloud`**, 설정 `%USERPROFILE%\.cloudflared\burnline.yml`
  (`api.unzipp.cloud`는 다른 터널이 쓰고 있어 피했다). 실행:
  `cloudflared tunnel --config %USERPROFILE%\.cloudflared\burnline.yml run burnline`
- `deploy/cloudflared/config.example.yml`: 경로별 라우팅 (nginx 대신). 도메인이 Cloudflare에 연결돼 있어야 한다.
- `deploy/vercel/vercel.example.json`: 프론트 저장소 루트에 `vercel.json`으로. 같은 출처라 CORS가 필요 없다.
- 심사 기간 내내 PC가 켜져 있어야 한다. 절전 끄기, `java -jar dist/*.jar`와 `cloudflared service install`을 자동 시작으로.
- DB 포트(5432)는 터널에 넣지 않는다.

## 데이터 가공 (적재기)

원본 배열을 "구역 × 달 한 줄"로 펼치면서 명세 코드값으로 바꾼다 (`*Loader`, 규칙은 `common/domain/Rules`).

| 원본 | 명세 |
|---|---|
| sufficiency 충분 / 부족 / 감지 없음 | region_type sufficient / sparse / none |
| fallback.ref_from 시·도 / 인접 시·도 | judge_unit province / nearby_province, ref = 대체 시·도 (CAL·BC는 주 전체) |
| basis 보정 25년 / VIIRS 2012~ | harmonized_25y / viirs_2012 |
| quality 높음 / 보통 / 낮음 | high / medium / low |
| NaN | null |
| 시·군·구 값 | `viirs` 블록 (2012-02~). 25년 `value`는 비교에 쓰지 않음 (원본 note 참고) |
| 시·군·구 한글 이름 | `common/src/main/resources/names/KOR_district_ko.csv` (원본에 없어서 만든 표, 229곳) |

판정(`JudgeService`): 칩 record → above → below → normal, 드문 지역은 ref로 빌려 쓰기, reason
PRE_2012_DISTRICT → NO_RECORD → IN_PROGRESS → NO_DETECTION → ZERO_BASELINE.

## 명세와 다르거나 확인이 필요한 것 (`TODO(A)`)

- **Butte 2018-11 ratio_to_median = 29.79** (1162/39, "비율은 두 자리"). 명세 7-13 예시는 29.8로 적혀 있다.
- **시·군·구·시·도 quality**: 원본에 VIIRS용 품질이 없어 같은 달 나라 품질을 쓰고, "평년 5칸 미만이면 한 단계 낮춤"(`Rules.lowerIfLowBaseline`)을 적용한다. 명세 7-5 의성 예시도 `medium`으로 고쳤다.
- **시·도 경계**: geoBoundaries ADM1이라 2023년 군위군 대구 편입 전 경계일 수 있다.

## 규칙

- 응답 이름·코드값은 명세 v1.0 그대로 (`common/domain/Codes`). 한글 문구는 프론트가 만든다.
- 에러는 `throw ApiException.xxx(...)` → `{"code", "message"}`.
- 응답은 data_version을 키로 Redis 캐시, ETag = data_version.
- 판정 로직은 `JudgeService` 하나에만. B의 insight는 `/internal/judge`로 부른다.
- 서비스끼리 DB를 공유하지 않는다. timeseries도 판정에 필요한 구역 정보를 원천에서 따로 적재한다.

## B 파트 (공간·사건·화면)

### spatial-service (:8083) — 적재 (`SpatialLoader`, 세 나라 합쳐 약 20초)

| 원천 | 표 | 규칙 |
|---|---|---|
| `output/{c}/hotspots_clean.csv` | `spatial.hotspots` | 0.01° 칸 × 현지 하루 하나. 화면 기준 위성만: 2012-02 전 MODIS, 2012-02~2018-03 S-NPP, 2018-04~ NOAA-20. 칸 키는 파이프라인과 같은 반올림 |
| `boundaries/{c}/` 원본 경계 (+ 한국 `patch-KOR-ADM2.geojson`) | (배정에만) | 경계 안이면 그 구역, 밖이면 3 km(0.03°) 안 꼭짓점이 가장 가까운 구역. 한 칸·하루에 점이 여러 개면 가장 많은 구역 (파이프라인 ⑯과 같음) |
| `calendar_by_region.json` viirs 블록 | `spatial.region_month` | 지도 색(`map_bin`)·TOP3 순위용 사본. 판정은 하지 않는다 (timeseries 담당) |
| `events.json`, `events_by_region.json` | `spatial.events`, `spatial.events_by_region` | 감지 묶음 (NOAA-20, 2018-04~) |
| `external/산불통계.xlsx` | `spatial.official_fires` (KOR) | 장소 이름으로 시·군·구 맞추기 (`OfficialFireReader.KoreanMatcher`): "의성" = "의성군", "수원장안" ⊃ "수원", 예외 군위(→대구)·영종(→중구)·검단(→서구)·세종. 7,628건 100% |
| `external/cal_fire_perimeters_2001_2025.csv` | 〃 (CAL) | 경계 중심점 좌표로 배정, 에이커 × 0.404686 → ha, 원인 코드 → 한국어 |
| `external/bc_fire_incidents_2001_2025.csv` | 〃 (BC) | `FIRE_TYPE = Fire`만, 날짜가 잘못된 줄은 버림, 연도는 `FIRE_YEAR` |
| `meta.json` + 위 값 | `spatial.landing` | 보정 전 연간 값(MODIS ~2011, S-NPP 2012~), 공식 피해면적 연 합계, 단절 검정, 크기별 감지율 |

응답 규칙:

- 달 범위는 2001-01 ~ 데이터 마지막 달 (한국 MODIS 점은 2000-11부터 있지만 A와 같이 2001-01부터). 밖이면 `MONTH_OUT_OF_RANGE`.
- `/events?from=&to=`: `Params.monthRange`로 형식과 순서를 함께 검사한다 (`from > to`면 `INVALID_PARAM`, A `/series`와 같음).
- `/official-fires`: 면적순, 같으면 최신 먼저 (명세 3장 #12).
- `/top?parent=시·군·구`: 자기 자신은 빼고 맞닿은 지역만 H 순.
- `/landing` `detect_by_size`: jsonb가 키 순서를 바꾸므로 원천 구간 순서(0.1 ha 미만 → 100 ha 이상)로 다시 정렬한다.

### insight-service (:8084)

- `/insight/{id}?month=`: catalog `/internal/regions` + timeseries `/internal/judge`(머리·ref·주변 지역)·`/series` + spatial `/internal/month-detail`를 동시에 불러 카드 1~9로 조합 (명세 7-8). 하위 서비스가 실패하면 그 카드만 `null`, 이름을 `unavailable`에 적는다.
  - 자기 시·도 판정 `prov{}`도 함께 준다 (빌려 쓴 시·도와 같으면 `ref`와 같은 값, CAL·BC는 null).
  - 드문 지역 `head.rank`·`n`은 `/judgement`가 null이라 자기 `/series`의 그달 순위를 쓴다.
  - 2012-02 전 달(`PRE_2012_DISTRICT`)은 `fire_days`·`dmax`·`dmax_date`도 null.
  - `map.center`는 `/regions`와 같은 중심점 (50 km 계산은 spatial이 꼭짓점 평균으로 따로 한다).
  - `neighbors[].province`: 같은 시·도 이웃은 자기 시·도로 채운다.
  - `years.longterm.quality`는 원천 한글(높음·보통·낮음)을 코드값(high·medium·low)으로 바꿔 준다.
- `/top?month=&parent=`: spatial `/internal/top` (나라·시·도: 순위 → H 순 3곳, `parent`가 시·군·구 ID면 맞닿은 지역 H 순).
- `/landing`: spatial `/internal/landing` + timeseries 나라 `/series`. `inconclusive`는 A `/meta`와 같은 `Rules.inconclusive` (보정 후 비율이 0.75~1.33 밖이면 true, BC 2.14). `risk_share`는 두 자리.
- ETag(`data_version`)은 spatial 응답의 값을 쓴다 (DB가 없어서).
- 캐시: 최근 달(partial·nrt)이 든 응답은 5분, 나머지는 하루 (A와 같은 기준). spatial은 `SourceFiles.noaa20ArchiveEnd`로 SP가 끝난 다음 달부터 최근 달로 보고, insight는 판정 `status`·나라 시계열 `status`를 본다.

### B 파트 로컬 실행 (Mac, Docker)

```bash
docker run -d --name burnline-db -p 55432:5432 -e POSTGRES_DB=burnline -e POSTGRES_USER=burnline -e POSTGRES_PASSWORD=burnline postgres:16
./gradlew dist
export DATABASE_URL=jdbc:postgresql://localhost:55432/burnline DATA_DIR=<burnline_backend_data 경로>
# 로더 규칙이 바뀐 뒤 처음 띄울 때는 다시 적재: export DATA_LOAD=always
for s in catalog timeseries spatial insight; do java -jar dist/$s-service.jar & done
curl "localhost:8084/api/v1/KOR/insight/91817680B75376031789242?month=2025-03"
```

명세 테스트: 서비스 4개를 띄운 뒤 `python api-test/spec_test.py --sweep` → `api-test/report.md`.

### 사이트와 다른 점 (확인 필요)

- **하루 최대 칸**: 의성 2025-03이 사이트 인사이트는 111칸, 여기는 108칸. 파이프라인 묶음(`events_by_region.peak_day_cells`)과 같은 규칙(칸×하루를 먼저 하나로 줄이고 구역을 정함)을 따라 108칸으로 맞췄다. 고성 2019-04는 경계선 위 점 하나 때문에 26~27칸.
- **50 km 안 첫 감지**: 의성 2025-03 사이트 330칸 → 여기 331칸 (같은 이유의 1칸 차이).
- **해운대구 2019-04**: 판정 값은 1칸인데 감지 점은 0개. 바다 쪽 경계 점 하나를 파이프라인과 다르게 배정한 차이 (위와 같은 종류).
