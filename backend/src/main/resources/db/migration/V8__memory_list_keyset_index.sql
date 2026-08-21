-- 기억 목록 키셋 페이지네이션 지원 인덱스.
-- 정렬 키는 (created_at DESC, id DESC) — id 는 같은 created_at 을 확정하는 타이브레이커.
-- 활성 목록 전체 스크롤용, 그리고 유형 필터가 걸릴 때를 위한 type 선행 인덱스.
-- 제목 검색(title ILIKE %q%)은 단일 사용자 규모에선 위 인덱스로 좁혀진 집합 스캔으로 충분하다
-- (대량 데이터에서 필요해지면 pg_trgm GIN 인덱스를 후속으로 추가).
CREATE INDEX idx_memory_status_created_id ON memory (status, created_at DESC, id DESC);
CREATE INDEX idx_memory_status_type_created_id ON memory (status, type, created_at DESC, id DESC);
