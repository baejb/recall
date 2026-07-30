# CLAUDE-SETUP-GUIDE.md — 먼저 읽으세요

Recall 저장소에서 Claude Code / 팀원이 **가장 먼저 읽는 가이드**. 파일이 어디에 있고, 무엇을
커밋하며, 결정은 어디에 기록하는지를 정의한다.

## 0. 30초 온보딩

```
1. 이 파일을 읽는다.
2. 루트 CLAUDE.md 의 "불변 원칙" 을 읽는다.
3. 작업 영역 CLAUDE.md 를 읽는다 (frontend / backend / nginx).
4. bash scripts/setup-hooks.sh 를 1회 실행해 git 훅을 켠다 (→ HOOKS-GUIDE.md).
5. templates/ 의 개인용 파일을 각자 위치로 복사한다 (§4).
```

## 1. 파일 배치와 책임

```
recall/
├── CLAUDE-SETUP-GUIDE.md   ← (이 파일) 먼저 읽을 가이드 · 커밋 규칙 · Decision Log
├── HOOKS-GUIDE.md          ← 훅 가이드 (자동 포맷 · 강제 검사)
├── CLAUDE.md               ← 루트 규칙 · 불변 원칙
├── frontend/CLAUDE.md      ← React/Vite/TS 규칙
├── backend/CLAUDE.md       ← Spring Boot/Java 규칙
├── nginx/CLAUDE.md         ← 리버스 프록시 규칙
├── .claude/
│   ├── settings.json       ← 팀 공유 권한 + 훅 등록 (커밋됨)
│   ├── skills/             ← 프로젝트 스킬 (recall-feature · git-commit · test)
│   └── hooks/format-on-edit.sh  ← 편집 시 자동 포맷 훅
├── .githooks/pre-commit    ← 커밋 강제 검사 게이트
├── scripts/setup-hooks.sh  ← git 훅 활성화 (각자 1회)
└── templates/              ← 각 개발자가 개인용으로 복사 (커밋 안 함)
    ├── user-global-CLAUDE.md   → ~/.claude/CLAUDE.md
    ├── CLAUDE.local.md         → CLAUDE.local.md
    └── settings.local.json     → .claude/settings.local.json
```

**CLAUDE.md 상속**: 하위 디렉터리에서 작업하면 **루트 + 해당 디렉터리 CLAUDE.md** 가 함께
적용된다. 하위 파일은 루트를 덮어쓰지 않고 **좁힌다**(더 구체적인 규칙만 추가).

## 2. 커밋 규칙

| 커밋한다 (팀 공유) | 커밋하지 않는다 |
|---|---|
| `*/CLAUDE.md`, `*-GUIDE.md`, `.claude/{settings.json,skills,hooks}` | `CLAUDE.local.md`, `.claude/settings.local.json` |
| `.githooks/`, `scripts/`, `templates/` | `~/.claude/CLAUDE.md` (개인 전역) |
| 소스(`frontend/src`, `backend/src`), 설정 | `.env`·키·`*.p12`·토큰, 빌드 산출물(`dist/`·`build/`) |

- **커밋 메시지에 AI/Claude 협업 흔적 금지** — `Co-Authored-By: Claude …`,
  `🤖 Generated with Claude Code`, "Claude가 작성" 류를 넣지 않는다. 커밋은 사람 작성물로만.
- Conventional Commits + scope: `feat(backend): …`, `fix(frontend): …`, `docs: …`.
- **기본 브랜치 직접 커밋 금지**. 작업 브랜치 → PR. (예: `project-setup`)
- pre-commit 훅을 우회(`--no-verify`)하지 않는다 (→ HOOKS-GUIDE.md).

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

## 4. 개인용 설정 (`templates/`)

| 템플릿 | 복사 위치 | 용도 |
|--------|-----------|------|
| `templates/user-global-CLAUDE.md` | `~/.claude/CLAUDE.md` | 전역 개인 취향(응답 언어 등), 모든 프로젝트 적용 |
| `templates/CLAUDE.local.md` | 루트 `CLAUDE.local.md` | 이 프로젝트 개인 메모(gitignore 됨) |
| `templates/settings.local.json` | `.claude/settings.local.json` | 개인 권한 오버라이드(공유 settings.json 을 덮어씀) |

팀 전체 규칙이면 개인 파일이 아니라 **공유 CLAUDE.md / settings.json** 에 넣고 PR 한다.
