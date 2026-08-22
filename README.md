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

지금은 **실행되는 최소 골격**이다(기능 미구현). 도메인 모듈은 기능 구현 단계에서 추가한다 —
목표 아키텍처·모듈 경계는 `docs/architecture.md`.

- `backend/` — Spring Boot 최소 골격 (부팅 · DB/pgvector 연결 · `GET /api/health`)
- `frontend/` — React + Vite SPA (백엔드 연결 확인 화면)
- `nginx/` — 리버스 프록시 (호스트 구동, 정적 서빙 + `/api` 프록시, SSE 통과)
- `docs/` — 설계 문서 (PRD·아키텍처·셋업·훅)

실행 확인: 백엔드 기동 후 `curl http://localhost:8080/api/health` → `{"success":true,"data":{"status":"ok","service":"recall"}}`.
모든 REST 응답은 공통 봉투(`{success,data}` / `{success,error}`)를 쓴다 — 스크립트는 `.data.status` 를 읽는다(SSE `POST /api/query` 만 예외).
문서 지도·온보딩은 `docs/setup.md` 먼저 읽기.
