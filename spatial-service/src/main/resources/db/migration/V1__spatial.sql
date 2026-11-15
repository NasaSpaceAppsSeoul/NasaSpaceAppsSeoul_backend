-- spatial 스키마 (Flyway default-schema = spatial). 서비스는 읽기만, 적재는 SpatialLoader.
-- 공간 DB(PostGIS) 없이 위경도 숫자로 둔다. 구역 배정은 적재할 때 끝낸다.

create table data_version (
  country    text primary key,
  version    text not null,                 -- meta.json generated (명세 data_version)
  loaded_at  timestamptz not null default now()
);

-- 구역: 점·기록 배정 결과와 인사이트에 필요한 사실값 (판정은 timeseries 담당)
create table regions (
  country      text not null,
  id           text not null,               -- shapeID / BC-RD-60
  name_en      text not null,
  label_ko     text not null,               -- 한국은 한글 이름(names/KOR_district_ko.csv), CAL·BC는 영문
  province     text,                        -- 한국 시·도 (CAL·BC는 null)
  center       jsonb not null,              -- [lon, lat] 원본 경계 꼭짓점 평균 (50 km 계산용)
  facts        jsonb not null,              -- annual, annual_all, longterm, yrs_fire_by_month 등
  primary key (country, id)
);

-- 감지 점: 0.01° 칸 × 현지 하루 하나 (화재 칸). 화면 기준 위성만:
-- 2012-02 전 MODIS, 2012-02~2018-03 S-NPP, 2018-04~ NOAA-20
create table hotspots (
  country    text not null,
  month      text not null,                 -- YYYY-MM
  day        smallint not null,
  lon        double precision not null,
  lat        double precision not null,
  sensor     text not null,                 -- MODIS | VIIRS | N20
  region_id  text                           -- 경계 안, 또는 3 km(0.03°) 안 가장 가까운 구역. 없으면 null (바다 등)
);
create index hotspots_month_idx on hotspots (country, month, region_id);
create index hotspots_region_idx on hotspots (country, region_id, month);

-- 시·군·구 월값 (지도 색·순위용 사본, 원천 calendar_by_region.json의 viirs 블록)
create table region_month (
  country    text not null,
  region_id  text not null,
  month      text not null,
  value      double precision,
  rank       int,
  n          int,
  primary key (country, region_id, month)
);
create index region_month_idx on region_month (country, month);

-- 감지 묶음 (NOAA-20, 2018-04~, 규칙 B: 3 km · 2일)
create table events (
  country     text not null,
  id          int not null,
  start_date  date not null,
  end_date    date not null,
  days        int not null,
  cells       int not null,
  peak_cells  int not null,
  peak_date   date not null,
  lat         double precision,
  lon         double precision,
  frp_mw      double precision,
  regions     jsonb not null,               -- [region_id, ...]
  primary key (country, id)
);
create index events_date_idx on events (country, start_date, end_date);

create table events_by_region (
  country         text not null,
  region_id       text not null,
  month           text not null,
  fire_days       int,
  peak_day_cells  int,
  events          int,
  big_events      int,
  primary key (country, region_id, month)
);

-- 공식 산불 기록: 산림청(KOR) · CAL FIRE FRAP(CAL) · BC Wildfire Service(BC)
create table official_fires (
  country    text not null,
  fire_date  date not null,
  month      text not null,
  place      text not null,
  cause      text not null,                 -- 한국어 (번개, 사람, 전선, 원인 미상 …)
  area_ha    double precision not null,
  region_id  text,                          -- 맞대지 못하면 null
  source     text not null
);
create index official_fires_idx on official_fires (country, month, region_id);

-- 랜딩용 원자료 (보정 전 연간 화재 칸, 공식 피해면적 연 합계)
create table landing (
  country  text primary key,
  doc      jsonb not null
);
