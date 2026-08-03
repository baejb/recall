# 셋업 가이드 — 먼저 읽으세요

Recall 저장소에서 Claude Code / 팀원이 **가장 먼저 읽는 가이드**. 온보딩 순서 · 문서 지도 ·
개인 설정 · 설계 결정 기록을 담는다.

> **규칙·저장소 구조·커밋 규칙의 단일 출처는 루트 `CLAUDE.md`** 다. 이 문서는 중복하지 않고 링크한다.

## 0. 30초 온보딩

```
1. 이 파일을 읽는다.
2. 루트 CLAUDE.md 의 "불변 원칙 · 저장소 구조 · 커밋 규칙" 을 읽는다.
3. 작업 영역 CLAUDE.md 를 읽는다 (frontend / backend / nginx).
4. bash scripts/setup-hooks.sh 를 1회 실행해 git 훅을 켠다 (→ docs/hooks.md).
5. templates/ 의 개인용 파일을 각자 위치로 복사한다 (§4).
```

## 1. 문서 지도

| 문서 | 내용 | 읽는 주체 |
|------|------|-----------|
| `CLAUDE.md` (루트 + frontend/backend/nginx) | 불변 원칙 · 스택 · 실행 · **저장소 구조** · **커밋 규칙** · 영역별 코딩 규칙 | Claude + 사람 |
| `docs/recall_ai_prd.md` | 기능·파이프라인 근거(단계 C·P·R·W·RR·A·M0·S2·S3·S4, 메모리 5유형) | Claude + 사람 |
| `docs/architecture.md` | 백엔드 아키텍처 결정(선택 이유·모듈 경계·확장 가드레일·분담) | 사람 |
| `docs/hooks.md` | 훅 가이드(편집 자동포맷 + 커밋 강제검사) | 사람 |
| `docs/development_v1.md` | 개발 버전 v1 요약 | 사람 |
| `.claude/{settings.json, skills/, hooks/}` | 팀 공유 권한 · 스킬(recall-feature·git-commit·test) · 자동포맷 훅 | Claude/도구 |

> **CLAUDE.md 상속**: 하위 디렉터리에서 작업하면 **루트 + 해당 디렉터리 CLAUDE.md** 가 함께
> 적용된다. 하위 파일은 루트를 덮어쓰지 않고 **좁힌다**(더 구체적인 규칙만 추가).

## 2. 커밋 — 무엇을 공유하고 무엇을 개인으로 두나

> 커밋 메시지 형식·금지 규칙(AI 흔적 금지·Conventional Commits·기본 브랜치 직접 커밋 금지·
> `--no-verify` 금지)의 **세부는 루트 `CLAUDE.md`의 "커밋 규칙"** 참고. 여기선 커밋 대상만 정리한다.

| 커밋한다 (팀 공유) | 커밋하지 않는다 |
|---|---|
| `*/CLAUDE.md`, `docs/`, `.claude/{settings.json,skills,hooks}` | `CLAUDE.local.md`, `.claude/settings.local.json` |
| `.githooks/`, `scripts/`, `templates/` | `~/.claude/CLAUDE.md` (개인 전역) |
| 소스(`frontend/src`, `backend/src`), 설정 | `.env`·키·`*.p12`·토큰, 빌드 산출물(`dist/`·`build/`) |

## 3. Decision Log (설계 결정 기록)

되돌리기 어렵거나 나중에 근거를 물을 결정(임계값·가중치·스택 선택 등)은 여기 append-only 로
남긴다. 삭제하지 않고, 번복 시 새 줄로 supersede 한다(원칙 "삭제 대신 상태 보존"과 동일).

| # | 날짜 | 영역 | 결정 | 이유 | 상태 |
|---|------|------|------|------|------|
| 1 | 2026-07-30 | data | 벡터/검색 저장소 = PostgreSQL + pgvector | 셀프호스트 1인 1 DB, 관계형+벡터+BM25를 한 엔진에서 | active |
| 2 | 2026-07-30 | arch | AI/LLM 구동 위치 미정(초기 Spring Boot 내부) | 부하/언어 이슈 시 별도 서비스 분리 여지 유지 | open |
| 3 | 2026-07-30 | infra | nginx 리버스 프록시(호스트 구동) | 정적 서빙 + /api 프록시 + SSE 통과 | active |
| 4 | 2026-07-30 | tooling | 포맷터 = prettier(front) / spotless google-java-format AOSP(back) | 기존 4칸 들여쓰기 유지, 훅으로 강제 | active |
| 5 | 2026-07-30 | scope | 배포까지 목표(로컬 실행만 아님) | 피드백 반영. 배포 구성은 추후 확정 | open |
| 6 | 2026-08-03 | arch | 백엔드 = 실용 계층형 모듈러 모놀리스 + LLM/임베딩만 경량 헥사고날, 유형별 전략 SPI | 2인·단일 인바운드·Postgres 고정 → 풀 헥사고날 이득<비용. 확장은 "type 추가" 축에 최적화 (→ `docs/architecture.md`) | active |

## 4. 개인용 설정 (`templates/`)

| 템플릿 | 복사 위치 | 용도 |
|--------|-----------|------|
| `templates/user-global-CLAUDE.md` | `~/.claude/CLAUDE.md` | 전역 개인 취향(응답 언어 등), 모든 프로젝트 적용 |
| `templates/CLAUDE.local.md` | 루트 `CLAUDE.local.md` | 이 프로젝트 개인 메모(gitignore 됨) |
| `templates/settings.local.json` | `.claude/settings.local.json` | 개인 권한 오버라이드(공유 settings.json 을 덮어씀) |

팀 전체 규칙이면 개인 파일이 아니라 **공유 CLAUDE.md / settings.json** 에 넣고 PR 한다.
