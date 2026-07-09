# Recall

개발자의 트러블슈팅 경험·학습 지식을 자동 추출해 구조화된 기억으로 보존하고, 근거와 함께 회수해주는 셀프호스트 개인 기억 시스템.

## 스택

- **Frontend** — React + TypeScript + Vite
- **Backend** — Spring Boot 4.0 (Java 25), 모듈러 모놀리스
- **DB** — PostgreSQL 17 + pgvector
- **Infra** — Docker Compose

## 실행

```bash
cp .env.example .env      # LLM API 키 등 입력
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- DB: localhost:5432

로컬 개발:

```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun
cd frontend && npm install && npm run dev
```

## 구조

```
backend/    Spring Boot (capture · intent · extraction · typerouter · review · memory · search)
frontend/   React + Vite SPA
```
