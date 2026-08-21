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

### 클론 위치 (Windows 필수 조건)

**저장소는 ASCII 경로 · 클라우드 동기화 폴더 밖에 둔다** (예: `C:\dev\recall`). 둘 다 도구가
조용히가 아니라 요란하게 깨지는 조건이고, 원인을 찾는 데 시간이 든다.

| 조건 | 어기면 |
|------|--------|
| 경로에 비ASCII 문자 금지 (사용자 이름 포함 — `C:\Users\홍길동\...` 도 해당) | `./gradlew test` 가 **모든 테스트 클래스를 `ClassNotFoundException`** 으로 실패시킨다(Gradle 테스트 워커 classpath 가 비ASCII 경로를 못 읽는다). 컴파일은 정상이라 원인이 안 보인다 |
| OneDrive·Dropbox 등 동기화 폴더 밖 | 동기화가 `build/` 의 `.class` 를 플레이스홀더로 바꿔 Gradle 이 `Cannot snapshot … not a regular file` 로 죽는다. 부분 컴파일 산출물로 이어져 증상이 들쭉날쭉하다 |

macOS·Linux 는 해당 없다.

## 1. 문서 지도

| 문서 | 내용 | 읽는 주체 |
|------|------|-----------|
| `CLAUDE.md` (루트 + frontend/backend/nginx) | 불변 원칙 · 스택 · 실행 · **저장소 구조** · **커밋 규칙** · 영역별 코딩 규칙 | Claude + 사람 |
| `docs/recall_ai_prd.md` | 기능·파이프라인 근거(단계 C·P·R·W·RR·A·M0·S2·S3·S4, 메모리 5유형) | Claude + 사람 |
| `docs/architecture.md` | 백엔드 아키텍처 결정(선택 이유·모듈 경계·확장 가드레일·분담) | 사람 |
| `docs/design/` | 슬라이스별 설계 문서(왜·무엇을·설계 판단·검증). 인덱스는 `docs/design/README.md` | 사람 |
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
| 7 | 2026-08-10 | search | 벡터 매핑 = JdbcTemplate native 문자열 리터럴(`CAST(? AS vector)`) | pgvector-java/hibernate-vector 의존성 추가 없이. memory_embedding은 JPA 엔티티 없이 native (→ `docs/design/knowledge-02-search.md`) | active |
| 8 | 2026-08-10 | search | 임베딩 provider = Voyage(voyage-3, 1024차원) BYO key, 미설정 시 stub | 프롬프트 LLM과 별개 키. 없어도 부팅·BM25 동작 | active |
| 9 | 2026-08-10 | search | RRF 융합 K=60, knowledge 채널 가중치 vector 2.0 / bm25 1.0 | 결정론 융합(LLM 금지). 지식은 vector 중심·bm25 보조(PRD). 라벨셋 fit 튜닝 대상 | active |
| 10 | 2026-08-11 | search | BM25 질의 = lexeme OR 결합(plainto_tsquery AND 아님) | 한국어 형태소 사전 없어 조사 차이로 AND 매칭 실패 | active |
| 11 | 2026-08-11 | store | S4 유사 임계 τ_sim=0.75, 판정 불확실 fallback = SUPPLEMENT | 후보는 있으니 NEW 아님, CONFLICT는 과함(자동 덮어쓰기 금지) → 사람 검토 유도 (→ `docs/design/knowledge-03-s4-judgement.md`) | active |
| 12 | 2026-08-21 | store | troubleshooting 카드 JSON = PRD snake_case 키, `attempts[]`는 `{action, result, outcome}` 객체 배열 | 저장된 structured JSON은 사실상 되돌리기 어렵다(기존 카드 마이그레이션 필요). 조치·결과 분리는 "뭘 시도했었지" 회상의 전제, `outcome`은 실패 시도 보존(🟠)의 기계 채점용 (→ `docs/design/troubleshooting-01-type.md`) | active |
| 13 | 2026-08-21 | store | troubleshooting `status` 모르는 값 → `UNRESOLVED`, `outcome` 모르는 값 → `unknown` | 해결됐다고 잘못 단정하는 쪽이 반대보다 위험(근거 없는 생성 금지의 연장). 실패로 위장하지도 않는다 | active |
| 14 | 2026-08-21 | search | troubleshooting 임베딩 kind = problem·solution 2개(PRD 이중 벡터), 채널 가중치 bm25 2.0 / vector 1.2 | attempts 3번째 kind는 임베딩 비용 1.5배 + PRD 이탈로 기각. 에러 코드·예외명은 정확 토큰 매칭이 벡터보다 강함(PRD §04). 라벨셋 fit 튜닝 대상 | active |
| 15 | 2026-08-21 | store | 저장 경로 유형 라우팅은 원문 앞 4,000자만 LLM에 넣는다 | 유형은 도입부에서 드러남 · 긴 붙여넣기 토큰 폭발 방지. 추출(S2/S3)은 전문을 겹침 청킹으로 커버하므로 내용 유실 아님(절단은 로그로 노출) | active |
| 16 | 2026-08-22 | tooling | google-java-format 1.25.2 → **1.28.0** | 1.25.2 가 JDK 25 에서 javac 내부 API 시그니처 변경으로 `NoSuchMethodError` — spotless 가 전 파일에서 죽어 포맷 게이트가 무력했다. 1.28.0 은 기존 코드 출력이 동일(포맷 churn 0) | active |
| 17 | 2026-08-22 | tooling | prettier `endOfLine: "auto"` + `.prettierignore` 에 `CLAUDE.md` | `.gitattributes` 가 TS/TSX 개행을 정규화하지 않아 Windows 체크아웃(CRLF)과 prettier 기본값(LF)이 충돌 → 새 클론에서 `format:check` 가 항상 실패하고 `npm run format` 이 37파일을 전부 다시 썼다. CLAUDE.md 는 prettier 가 마크다운 강조 표기를 바꿔 규칙 문서를 훼손 | active |
| 18 | 2026-08-22 | infra | 저장소는 **ASCII 경로 · 동기화 폴더 밖**에 둔다(이 머신 기준 `C:\dev\recall`) | 비ASCII 경로에서 Gradle 테스트 워커가 테스트 클래스를 못 읽어 `./gradlew test` 전량 실패, OneDrive 가 `build/` 를 플레이스홀더로 바꿔 Gradle 스냅샷이 깨진다. 빌드 출력만 옮기는 우회보다 클론 위치를 옮기는 쪽이 표준 명령을 그대로 쓰게 해준다(§0) | active |

## 4. 개인용 설정 (`templates/`)

| 템플릿 | 복사 위치 | 용도 |
|--------|-----------|------|
| `templates/user-global-CLAUDE.md` | `~/.claude/CLAUDE.md` | 전역 개인 취향(응답 언어 등), 모든 프로젝트 적용 |
| `templates/CLAUDE.local.md` | 루트 `CLAUDE.local.md` | 이 프로젝트 개인 메모(gitignore 됨) |
| `templates/settings.local.json` | `.claude/settings.local.json` | 개인 권한 오버라이드(공유 settings.json 을 덮어씀) |

팀 전체 규칙이면 개인 파일이 아니라 **공유 CLAUDE.md / settings.json** 에 넣고 PR 한다.
