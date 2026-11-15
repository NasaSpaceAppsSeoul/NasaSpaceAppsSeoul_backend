-- 보정 전 값 추가 (히트맵 보정 전/후 전환용)
ALTER TABLE series ADD COLUMN IF NOT EXISTS raw_before double precision;

-- 새 컬럼이 추가됐으므로 다음 기동 시 전체 재적재
DELETE FROM data_version;
