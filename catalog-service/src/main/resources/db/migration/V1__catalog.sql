-- catalog 스키마 (Flyway default-schema = catalog). 서비스는 읽기만, 적재는 CatalogLoader.

create table data_version (
  country    text primary key,
  version    text not null,                 -- meta.json generated (명세 data_version)
  loaded_at  timestamptz not null default now()
);

create table meta (
  country  text primary key,
  doc      jsonb not null                   -- meta.json 전체
);

create table regions (
  country       text not null,
  id            text not null,              -- KOR / 경북 / geoBoundaries shapeID / BC-RD-60
  level         text not null,              -- nat | prov | dist
  label_ko      text not null,
  name_en       text,
  province      text,                       -- 소속 시·도 (CAL·BC는 null)
  parent_id     text,                       -- dist → 시·도(KOR) 또는 나라(CAL·BC), prov → 나라
  region_type   text not null,              -- sufficient | sparse | none
  judge_unit    text not null,              -- self | province | nearby_province
  ref_province  text,                       -- 판정을 빌려 오는 지역 ID
  basis         text not null,              -- harmonized_25y | viirs_2012
  risk_months   jsonb not null,
  risk_share    double precision,
  risk_from     text,
  center        jsonb,                      -- [lon, lat]
  bbox          jsonb,                      -- [minLon, minLat, maxLon, maxLat]
  neighbors     jsonb not null,             -- [id, ...]
  sort_order    int not null,
  props         jsonb not null,             -- 원본 속성 (regions.geojson properties 등)
  geometry      jsonb,                      -- GeoJSON geometry, 좌표 소수점 3자리
  primary key (country, id)
);
create index regions_level_idx on regions (country, level, sort_order);
create index regions_parent_idx on regions (country, parent_id);
