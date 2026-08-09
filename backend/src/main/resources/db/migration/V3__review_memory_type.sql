-- 검토 항목이 어떤 유형의 memory를 제안하는지. 승인 시 이 유형으로 memory를 만든다.
-- 기존 마이그레이션(V2)은 수정하지 않고 새 버전으로만 추가한다(되돌릴 수 없음).
ALTER TABLE review_queue ADD COLUMN memory_type VARCHAR(32);
