# Recall — 실행 골격 (v1 재구성)

> **실행되는 최소 골격**. 기능 코드는 없다 — 부팅·DB 연결·헬스체크·프론트 표시까지만.
> 도메인 모듈(capture/search/review 등)과 AI 로직은 이후 단계에서 추가한다.
> 저장소: https://github.com/baejb/recall · 설계 기준: 별도 Recall AI PRD

## 스택

| 레이어 | 기술 |
|--------|------|
| Frontend | React 19 + TypeScript + Vite 6 |
| Backend | Spring Boot 4.0 (Java 25) |
| DB | PostgreSQL 17 + pgvector, Flyway 마이그레이션 |
| Infra | Docker(=**DB만**), nginx(호스트 리버스 프록시) |

## 실행

```bash
cp .env.example .env
docker compose up -d                       # PostgreSQL + pgvector (:5432)
cd backend && ./gradlew bootRun            # :8080
cd frontend && npm install && npm run dev  # :3000 (vite proxy → /api)
```

확인: `curl http://localhost:8080/api/health` → `{"success":true,"data":{"status":"ok","service":"recall"}}`
(모든 REST 응답이 공통 응답 형식을 쓴다 — 규칙은 `docs/conventions/java-spring.md` §2).
프론트(:3000) 화면에 "백엔드: 연결됨 (ok)" 표시.

## 현재 골격에 있는 것

### 백엔드 (`com.recall`)
- `RecallApplication` — Spring Boot 진입점.
- `HealthController` — `GET /api/health`(실행 확인용).
- `WebConfig` — 개발용 CORS(Vite :3000). 배포에선 nginx 가 동일 오리진 프록시.
- `application.yml` — datasource(pgvector) · JPA(`ddl-auto=none`) · Flyway · actuator health.
- Flyway `V1__init.sql` — pgvector 확장 활성화만(baseline). 도메인 테이블 없음.

### 프론트엔드
- 최소 SPA — `/api/health` 를 호출해 백엔드 연결 여부만 표시. 표준 Vite 템플릿.

### nginx
- `nginx/nginx.conf` — 빌드된 SPA 정적 서빙 + `/api` 프록시(SSE 위해 `proxy_buffering off`).

## 다음 단계 (기능 구현 시)

- Flyway V2+ 로 도메인 스키마(capture · memory · review_queue · audit …) 추가.
- 저장 경로(마스킹·추출·검토 게이트) / 조회 경로(분류·검색·답변) 모듈 구현 —
  `.claude/skills/recall-feature` 스킬 절차를 따른다(불변 원칙 게이트 + Eval).
- 프론트 검토/검색 UI, LLM(BYO key) 연동.
