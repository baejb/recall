# Recall — 개발 버전 v1

> 초기 스캐폴드. "실행되는 뼈대"까지 구현하고, AI 로직은 `TODO`로 표시.
> 저장소: https://github.com/baejb/recall

## 스택

| 레이어 | 기술 |
|--------|------|
| Frontend | React 19 + TypeScript + Vite 6 |
| Backend | Spring Boot 4.0 (Java 25), 모듈러 모놀리스 |
| DB | PostgreSQL 17 + pgvector, Flyway 마이그레이션 |
| Infra | Docker(=**DB만**), nginx(호스트 리버스 프록시) |
| LLM | 외부 API · BYO key |

## 실행

```bash
cp .env.example .env
docker compose up -d                       # PostgreSQL + pgvector
cd backend && ./gradlew bootRun            # :8080
cd frontend && npm install && npm run dev  # :3000 (vite proxy → /api)
```

## 구현 상태

### 백엔드 (컴파일 통과 ✅)

모듈러 모놀리스 — `com.recall.*`

| 모듈 | 상태 | 내용 |
|------|------|------|
| `capture` | **동작** | 마스킹(정규식: API키/토큰/비번, 외부 전송 전 선행) + 원문 보존 + `POST /api/captures` |
| `review` | **동작** | 검토 대기함(HITL). 목록/카운트/승인/반려 |
| `extraction` | 흐름만 | 저장→검토 연결은 되나 실제 추출은 스텁(placeholder pending 생성), `@Async` |
| `intent` | 스텁 | 저장/조회 + 세부 intent 분류 (기본값 반환, LLM 미연동) |
| `typerouter` | 스텁 | 유형 라우팅 + 재유입 판정(신규/재발/보완/충돌) |
| `search` | 스텁 | 질문분류→플래너→하이브리드 검색→답변 재구성. `POST /api/query` SSE, 근거 없으면 "기록 없음" |
| `memory` | 스텁 | 엔티티/조회 (`GET /api/memories`) |
| `llm` | 스텁 | 외부 LLM 클라이언트 래퍼(BYO key), complete/embed 미구현 |
| `common` | - | audit 로그, 전역 예외 처리, dev CORS |

### API 엔드포인트

- `POST /api/captures` — 원문 저장(마스킹 후) → 비동기 추출 트리거 → `202`
- `GET  /api/reviews` · `GET /api/reviews/count` — 검토 대기 목록/건수
- `POST /api/reviews/{id}/approve` · `/reject` — 승인/반려
- `POST /api/query` — 질문(SSE 스트리밍 답변)
- `GET  /api/memories` · `GET /api/memories/{id}` — 메모리 조회

### DB 스키마 (Flyway `V1__init.sql`)

`capture` · `memory`(problem/solution/fact/document 임베딩 vector(1024) + tsvector) ·
`memory_capture`(N:M) · `review_queue` · `audit`. HNSW/GIN 인덱스.
단일 사용자 원칙 → `user` 테이블/`user_id` 없음.

### 프론트엔드

최소 SPA 1개 — 검토 대기 건수 표시(백엔드 연동 확인용). 표준 Vite 템플릿.

## 반영된 핵심 규칙 (문서 기반)

- 마스킹 선행 (외부 LLM 전송 전) — 구현
- 승인 게이트: 자동 저장 없음, 전부 review_queue 경유 — 구조 반영
- 근거 없는 답변 금지 — Answer Composer가 후보 없으면 "기록 없음" 반환
- 경로 분리: 저장=async / 조회=동기 SSE

## 미구현 (다음 단계)

- LLM 실제 연동(분류/추출/판정/답변 재구성, 임베딩 생성)
- Map-Reduce 추출 + JSON 스키마 강제
- 하이브리드 검색(exact/BM25/pgvector 병렬) + Weighted RRF + Reranker
- 승인 시 Memory 영속 + 임베딩 생성 트랜잭션
- 프론트 검토/검색 UI

## 커밋 히스토리

1. `chore: initialize recall repository`
2. `feat(backend): Spring Boot modular monolith scaffold`
3. `feat(frontend): React + Vite SPA scaffold`
4. `feat(infra): Docker Compose (pgvector + backend + frontend)`
5. `refactor(infra): Docker manages DB only; nginx as host reverse proxy`
