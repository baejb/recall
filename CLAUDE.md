# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 응답 언어는 **한국어**. 코드·식별자·로그·커밋 메시지 원문은 그대로 둔다.
> 이 문서는 **프로젝트를 시작하기 위한 규칙/셋팅**을 정의한다. 특정 기능 구현 상세는 담지 않는다.

## 프로젝트 개요

**Recall** — 단일 사용자 셀프호스트 개인 기억 시스템. 개발자의 트러블슈팅·학습·결정이 흩어진
대화 로그를 구조화된 memory로 저장하고, 자연어 질문으로 **근거와 함께** 회상한다.

설계 기준은 별도 **Recall AI PRD** 문서이며, 아래 **불변 원칙**은 전 코드/전 영역에 적용된다.
기능을 만들기 전에 이 원칙과 해당 영역 CLAUDE.md를 먼저 확인한다.

## 불변 원칙 (구현·리뷰의 기준)

1. **자동 저장 없음 · 승인 게이트** — 추출된 어떤 것도 검토 대기(review) 승인 전에는 영구
   memory에 쓰지 않는다.
2. **마스킹 우선** — 원문이 외부 LLM/인덱스/로그로 나가기 **전에** 값 마스킹을 수행한다. 이
   순서를 바꾸는 변경은 🔴 치명(민감정보 유출).
3. **삭제 대신 상태 보존** — 기록은 지우지 않고 상태(`active|superseded|incorrect` 등)로 전이한다.
   충돌은 **자동 덮어쓰기 금지**, 두 기록 보존 후 검토 요청.
4. **결정론 단계에 LLM 금지** — 검색 플래너/하이브리드 검색/가중치 융합은 결정적으로 유지
   (재현·감사·비용 통제). LLM은 모호성이 본질인 단계(분류·리랭크·답변·추출·판정)에만.
5. **근거 없는 생성 금지** — 답변 문장은 저장된 근거에 매인다. 근거가 없으면 지어내지 않고
   "기록 없음"을 반환.
6. **조용한 실패/절단 금지** — 실패한 단계·잘린 입력은 항상 상태로 노출한다.

## 스택

| 레이어 | 기술 |
|--------|------|
| Frontend | React 19 + TypeScript + Vite 6 (SPA) — 세부 규칙 → `frontend/CLAUDE.md` |
| Backend | Spring Boot 4.0 (Java 25), 모듈러 모놀리스 — 세부 규칙 → `backend/CLAUDE.md` |
| DB | PostgreSQL 17 + pgvector, Flyway 마이그레이션 |
| Reverse Proxy | nginx (호스트 구동) — 세부 규칙 → `nginx/CLAUDE.md` |
| LLM | 외부 API · BYO key (강/저가/임베딩 모델 분리 설정) |
| Infra | Docker Compose (개발 중엔 **DB만**), 배포 구성은 추후 |

## 실행 / 빌드 / 테스트 (로컬 개발)

```bash
cp .env.example .env         # DB 접속 + LLM API 키(BYO) 입력
docker compose up -d         # PostgreSQL + pgvector on :5432

cd backend && ./gradlew bootRun            # http://localhost:8080  (Windows: gradlew.bat)
cd frontend && npm install && npm run dev  # http://localhost:3000  (vite가 /api → :8080 프록시)
```

- 백엔드 테스트: `./gradlew test` (단일: `./gradlew test --tests 'com.recall.SomeTest.method'`)
- 프론트 빌드: `npm run build` (`tsc --noEmit` 타입체크 후 `vite build`)
- 스키마는 **Flyway 소유**(`ddl-auto=none`). 스키마 변경은 엔티티가 아니라
  `backend/src/main/resources/db/migration/V__*.sql` 새 파일로만.

> **배포**: 이 프로젝트는 로컬 실행에 그치지 않고 **배포까지** 목표로 한다(셀프호스트 단일
> 사용자 기준). 배포 구성(빌드 산출물·nginx 서빙·컨테이너/서비스화)은 추후 별도 정리한다.
> 지금 단계 문서는 로컬 시작 기준으로만 확정한다.

## 저장소 구조 (셋팅 관점)

```
recall/
├── CLAUDE.md              ← (이 파일) 루트 규칙 · 불변 원칙
├── frontend/CLAUDE.md     ← React/Vite/TS 규칙
├── backend/CLAUDE.md      ← Spring Boot/Java 규칙
├── nginx/CLAUDE.md        ← 리버스 프록시 규칙
├── .claude/skills/        ← 프로젝트 전용 스킬
├── frontend/              ← SPA (Vite)
├── backend/               ← Spring Boot 모듈러 모놀리스 (com.recall.*)
└── docker-compose.yml     ← 개발용 DB(pgvector)
```

**CLAUDE.md 상속**: 하위 디렉터리 작업 시 **루트 + 해당 디렉터리 CLAUDE.md**가 함께 적용된다.
하위 파일은 루트를 덮어쓰지 않고 **좁힌다**(더 구체적인 규칙만 추가).

## 관례

- 응답·문서는 한국어. 코드·식별자·로그·커밋 메시지 원문은 유지.
- 되돌리기 어려운 설계 선택(임계값·가중치·스택)은 커밋 메시지에 근거를 남긴다.
- 스키마 변경은 Flyway 새 마이그레이션으로만. 엔티티로 스키마를 만들지 않는다.

### 커밋 규칙

- **커밋 메시지에 AI/Claude 협업 흔적을 넣지 않는다** — `Co-Authored-By: Claude …`,
  `🤖 Generated with Claude Code`, "Claude가 작성" 류의 trailer/문구를 추가하지 말 것.
  커밋은 사람 작성물로만 남긴다.
- Conventional Commits + 영역 scope 사용: `feat(backend): …`, `fix(frontend): …`,
  `chore(nginx): …`, `docs: …`.
- 기본 브랜치에 직접 커밋하지 않는다. 작업 브랜치 → PR. (오늘 작업 브랜치: `project-setup`)
- 비밀(`.env`, 키, `*.p12`, 토큰)은 커밋 금지.
