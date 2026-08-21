-- 재색인 세대(generation) 토큰. 임베딩 설정이 바뀔 때마다 1 증가시켜 배경 재색인 잡에 부여한다.
-- 직렬화(단일 스레드 executor) + "자기 세대가 아직 현재 세대와 같을 때만 종료 상태(READY/FAILED)를
-- 쓴다"는 펜싱으로, 앞선 잡이 새 재색인이 진행 중인 인덱스 위에 READY 를 덮어쓰는 것을 막는다.
ALTER TABLE model_setting ADD COLUMN embedding_generation BIGINT NOT NULL DEFAULT 0;
