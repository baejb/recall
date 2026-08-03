# Recall

개발자의 트러블슈팅 경험·학습 지식을 자동 추출해 구조화된 기억으로 보존하고, 근거와 함께 회수해주는 셀프호스트 개인 기억 시스템.

## 스택

- **Frontend** — React + TypeScript + Vite
- **Backend** — Spring Boot 4.0 (Java 25), 모듈러 모놀리스
- **DB** — PostgreSQL 17 + pgvector
- **Infra** — Docker Compose

## 실행

Docker로는 DB(pgvector)만 띄우고, 백엔드·프론트는 로컬에서 실행한다.

```bash
cp .env.example .env      # DB / LLM API 키 등 입력

docker compose up -d      # PostgreSQL + pgvector (localhost:5432)

cd backend && ./gradlew bootRun          # http://localhost:8080
cd frontend && npm install && npm run dev # http://localhost:3000
```

## 구조

- `backend/` — Spring Boot 모듈러 모놀리스 (모듈 경계·아키텍처 → `docs/architecture.md`)
- `frontend/` — React + Vite SPA
- `nginx/` — 리버스 프록시 (호스트 구동)
- `docs/` — 설계 문서 (PRD·아키텍처·셋업·훅)

문서 지도·온보딩은 `docs/setup.md` 먼저 읽기.
