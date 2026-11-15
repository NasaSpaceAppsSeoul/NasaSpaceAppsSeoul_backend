-- 위험 시기의 정점 달 (기준 기간 달별 합이 가장 큰 달). 나라 전체 대시보드 "평소 화재 집중 시기" 카드용
ALTER TABLE regions ADD COLUMN IF NOT EXISTS peak_month int;

-- 새 컬럼이 추가됐으므로 다음 기동 시 전체 재적재
DELETE FROM data_version;
