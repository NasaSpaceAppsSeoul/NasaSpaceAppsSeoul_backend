-- timeseries 스키마 (Flyway default-schema = timeseries). 서비스는 읽기만, 적재는 TimeseriesLoader.

create table data_version (
  country    text primary key,
  version    text not null,
  loaded_at  timestamptz not null default now()
);

-- 판정에 필요한 구역 정보. catalog와 같은 원천에서 따로 적재한다 (서비스끼리 DB를 공유하지 않음).
create table regions (
  country       text not null,
  id            text not null,
  level         text not null,             -- nat | prov | dist
  label_ko      text not null,
  region_type   text not null,             -- sufficient | sparse | none
  judge_unit    text not null,             -- self | province | nearby_province
  ref_id        text,                      -- 판정을 빌려 오는 지역
  basis         text not null,             -- harmonized_25y | viirs_2012
  period_start  int  not null,             -- 순위·평년 비교 기간
  period_end    int  not null,
  heat_bins     jsonb not null,            -- 0이 아닌 월값의 50·75·90 퍼센타일
  primary key (country, id)
);

-- 구역 × 달 한 줄. 명세 7-3 필드 그대로.
create table series (
  country          text not null,
  region_id        text not null,
  month            text not null,          -- YYYY-MM
  value            double precision,       -- null = 값 없음 (시·군·구 2012-02 전 등)
  source           text,
  quality          text,
  estimated        boolean not null,
  status           text not null,          -- final | partial | nrt
  rank             int,
  n                int,
  ratio_to_median  double precision,
  primary key (country, region_id, month)
);

-- 구역 × 달(1~12) 평년·보통 해 범위
create table baseline (
  country    text not null,
  region_id  text not null,
  m          int  not null,
  median     double precision,
  q25        double precision,
  q75        double precision,
  band_low   double precision,
  band_high  double precision,
  primary key (country, region_id, m)
);
