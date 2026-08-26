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
| `docs/conventions/java-spring.md` | **구조 규칙**: common 폴더 구성 · 컨트롤러 응답 형식 · 예외 종류·핸들러 · Map 금지 · 문자열 상수화 · 테스트 기준 | Claude + 사람 |
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
| 2 | 2026-07-30 | arch | AI/LLM 구동 위치 미정(초기 Spring Boot 내부) | 부하/언어 이슈 시 별도 서비스 분리 여지 유지 | **superseded (43 참조)** |
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
| 19 | 2026-08-22 | web | 모든 REST 응답을 공통 응답 형식 `ApiResponse<T>`(`{success,data}` / `{success,error}`)로 통일. SSE(`/api/query`)만 예외 | 성공은 원본 DTO·실패는 ProblemDetail 이라 형태가 비대칭이었고, 호출자가 본문을 파싱하기도 전에 상태 코드로 갈라야 했다. 그 분기가 프론트에 두 번 구현돼 있었다(공통 `request()` + `updateSettings` 전용 fetch) (→ `docs/conventions/java-spring.md` §2) | active |
| 20 | 2026-08-22 | exception | 예외는 `ApiException` 하위 타입 + `ErrorCode` 열거(코드가 HTTP 상태를 보유) + 전역 핸들러 하나. `IllegalState`/`IllegalArgument` 를 HTTP 경계까지 올리지 않는다 | "모든 IllegalArgumentException = 400" 규칙이 내부 배선 버그를 400 으로 감췄고, 반대로 "이미 처리된 검토 항목"(IllegalStateException)은 catch-all 에 걸려 500 으로 나갔다 — 호출자가 고칠 수 있는 상황이 서버 장애로 보고됐다 (→ `java-spring.md` §3) | active |
| 21 | 2026-08-22 | web | `Map` 을 응답·요청·모듈 경계 계약으로 쓰지 않는다. 반복 문자열은 소유 도메인이 상수(닫힌 집합은 enum)로 갖는다 | Map 키는 계약인데 컴파일러가 지켜 주지 않아 이름 변경이 컴파일 에러 0개로 통과했다. 채널 이름은 문자열 오타가 `RrfFusion.getOrDefault(1.0)` 에 조용히 삼켜져 유형별 가중치 설계를 무효화했다 (→ `java-spring.md` §4·§5) | active |
| 22 | 2026-08-22 | 구조 | `common/` 을 주제별 폴더로 분리(`config`·`exception`·`web`·`type`·`prompt`·`secret`) | 리프 모듈에 배선·도메인 커널·유틸이 평평하게 섞여 있어 파일 이름으로 종류를 추측해야 했다. 폴더는 개수가 아니라 **종류**로 쪼갠다 (→ `java-spring.md` §1) | active |
| 23 | 2026-08-22 | exception | provider 실패를 갈라 분류: provider 가 **4xx** 로 답하면 400(내 입력 문제), **5xx·연결 실패·분류 불가**는 502 `UPSTREAM_UNAVAILABLE` | 설정 저장 전 임베딩 프로브가 provider 의 모든 실패를 400 으로 뭉개서, provider 장애 중에 정상 설정을 저장하려는 사용자에게 "키·모델을 확인하라"고 틀린 안내를 하고 상류 장애가 5xx 모니터링에서 사라졌다 (→ `java-spring.md` §3) | active |
| 24 | 2026-08-22 | store | S3 긴맥락 병합에서 "마지막 조각 값이 이겨야 하는 필드"를 **유형이 지목**한다(`ExtractionStrategy.lastWinsFields()`) — 트러블슈팅은 `status` | 공유 병합기의 "첫 non-blank" 규칙이 서술 필드엔 맞지만 시간 순서가 있는 상태값엔 정반대다: 앞 조각의 UNRESOLVED 가 이겨 원인·해결이 다 적힌 카드의 상태만 미해결로 남았다. 어떤 필드가 상태값인지는 유형별 지식이라 공유 코드가 알면 가드레일 2 위반이므로 판단을 유형에 되묻는다 | **superseded (26·27 참조)** |
| 25 | 2026-08-22 | store | S3 병합 결과 검증을 **모양 + 내용** 두 단계로: 부분 카드가 채웠던 필드가 병합 결과에서 비면 결정론 병합으로 격하 | 키 하나만 공유하면 통과하던 느슨한 모양 검사가, `{"title":"병합됨"}` 같은 응답을 통과시키고 코덱이 빠진 리스트를 빈 리스트로 정규화해 **추출된 사실을 조용히 삭제**했다. "Reduce 실패 시 사실을 유실하지 않는다"는 규약이 LLM 이 실패할 때만 지켜지고 있었다 | active |
| 26 | 2026-08-22 | exception | 에러 응답은 **컨텐츠 협상 대상이 아니다** — 전역 핸들러가 `Content-Type: application/json` 을 못 박는다 | 응답 객체를 그냥 반환하면 Accept 협상 실패가 핸들러 안에서 터져 원 예외가 재전파되고 **500 + 빈 본문**이 나간다. SSE 소비자(`Accept: text/event-stream`)에게 409·400 이 모두 빈 본문으로 갔다(라이브 재현). 구 `ProblemDetail` 에 있던 프레임워크 폴백이 공통 형식 전환으로 사라진 것 (→ `java-spring.md` §3) | active |
| 27 | 2026-08-22 | store | S3 결말 필드는 **위치**(첫/마지막 조각)로 고르지 않는다 — 유형이 `reconcileMerged()` 에서 "결말을 말한 마지막 조각"을 고른다. 결정론·LLM 병합 양쪽이 같은 훅을 거친다 | Decision 24(`lastWinsFields`)를 폐기한다. 조각 추출이 status 를 항상 채우므로(모르는 값 → UNRESOLVED) "마지막 non-blank" ≡ "무조건 마지막 조각"이 됐고, 결말을 말하지 않은 꼬리 조각(검증 로그·잡담)의 기본값이 앞의 RESOLVED 를 덮었다 — 고치려던 모순의 거울상. 위치로는 결말을 알 수 없다 | active |
| 28 | 2026-08-22 | 구조 | 카드 되읽기 실패는 **건너뛸 수 있는 경로에서만 격하**한다(`CardCodec.readOrNull`): 조회·S4·재색인은 그 한 건을 빼고 계속, 승인은 409 로 분류 | 코덱은 unknown 필드엔 관용이지만 값 모양 불일치는 던진다. 그 행이 한 건 있으면 네 경로가 연쇄로 죽었다 — 답변 격하 장치가 같은 read 로 또 던져 질의 전체가 에러가 되고, 재색인은 잡 전체 FAILED → `embedding_status=FAILED` → 그 사용자의 **벡터 채널이 통째로 꺼졌다**(게다가 영원히 못 읽으니 고착) | active |
| 29 | 2026-08-22 | 코딩 | 토큰 정규화는 **`Locale.ROOT`** 로 한다(기본 로케일 `toUpperCase`/`toLowerCase` 금지) | 터키어 로케일에서 `i → İ` 로 올라가, `anthropic`·`openai`·`RESOLVED` 처럼 `i` 를 담은 토큰이 매칭에 실패한다 → 모든 카드가 조용히 UNRESOLVED 로 격하되고 provider 조회가 전부 빈다. `MemoryTypeMatch` 한 곳만 규칙을 지키고 7개 파일이 기본 로케일이었다 (→ `java-spring.md` §3) | active |
| 30 | 2026-08-22 | store | ~~S3 병합 검증에 **리스트 개수 하한**을 둔다~~: 병합 결과의 항목 수가 어느 한 조각보다도 적으면 격하(알맹이 없는 항목은 세지 않음) | "비어있지 않다"만 보면 attempts 5건이 `[{}]` 하나로 줄어도 통과하고 코덱이 빈 Attempt 로 정규화해 시도 이력이 실질 유실됐다(🟠). 병합은 union 이므로 결과는 어느 한 조각의 리스트도 포함해야 한다 — dedup 으로는 깨질 수 없는 하한 | **superseded (34 참조)** |
| 31 | 2026-08-22 | store | S4 kind 스코프의 **재현율 손실을 주석에 명시**하고, 벡터·BM25 최상위가 어긋나면 warn 으로 드러낸다 | kind 필터는 잘못된 짝(증상 vs 해결책)만 빼는 게 아니라 대표 kind 임베딩이 없는 기존 카드를 벡터 단계에서 불가시화한다 — 진짜 중복이 후보에서 탈락해 중복 memory 가 생길 수 있다. 질의 벡터가 하나뿐이라 kind 별 임베딩 없이는 구조적 한계이므로, 없애는 대신 관측 가능하게 만든다 | active |
| 32 | 2026-08-22 | 구조 | 도메인 모듈도 **계층별 폴더**(`controller/`·`service/`+`service/entity/`·`repository/`·`config/`)로 쪼개고, **모듈 root 는 공개 계약만** 남긴다. `service/impl/` 은 만들지 않고 포트 모듈 `llm/` 은 제외 | `common/` 만 주제별로 쪼개고 모듈은 평평하게 둔 동안 "이 모듈의 공개 API가 무엇인가"에 답할 방법이 없었다 — 엔티티·리포지토리가 유스케이스와 같은 층에 있어 `public` 하나로 모듈 밖에 그대로 노출됐다. 쪼개자 **경계 침범이 import 줄로 드러났다**: 다른 모듈의 `service.entity`(Memory·Capture·ReviewItem)와 `repository`(MemoryRepository·MemorySearchStore·CaptureRepository·ReviewRepository·ModelSettingRepository)를 직접 import 하는 곳이 28군데(import 줄 기준) — 남의 리포지토리 11 · 남의 엔티티 14 · 엔티티끼리 JPA 연관 3 — `backend/CLAUDE.md` "다른 모듈은 그 모듈의 public 서비스로만 접근" 위반. 이동은 드러내기까지고, 실제 제거는 39~42번에서 했다(34 → 6건) (→ `java-spring.md` §1) | active |
| 33 | 2026-08-22 | 테스트 | **테스트를 위해 프로덕션 가시성을 넓히지 않는다** — package-private/protected 멤버가 필요하면 테스트 소스 루트에 같은 패키지 픽스처를 둔다 | 레이아웃을 쪼개자 테스트가 프로덕션 내부를 찌르고 있던 게 드러났다: `ModelSetting` 의 인자 없는 생성자는 JPA 전용 `protected` 인데 **프로덕션은 이 엔티티를 한 번도 생성하지 않아**(행은 Flyway 가 넣는다) 유일한 사용자가 테스트였다. 생성자를 `public` 으로 올리면 테스트 편의로 프로덕션 API 를 여는 것이라, `test/…/service/entity/ModelSettingFixture` 로 해결 | active |
| 34 | 2026-08-23 | store | S3 병합에서 **리스트는 결정론 union 이 소유**한다 — LLM 응답의 리스트 필드는 읽지 않고 union 으로 교체(30번 대체) | 개수 하한은 **교차 유실을 통과시켰다**: 조각 `[a,b]`·`[c,d]` → 병합 `[a,c]` 는 하한(가장 긴 조각=2)을 만족하며 b·d 를 잃는다. 하한을 union 수로 올리면 겹침 청킹이 만든 "표현만 다른 중복"의 정상 dedup 까지 유실로 오판해 LLM 병합이 사실상 죽고, 소속으로 증명하려 해도 LLM 이 문장을 다시 쓰면 동일성 비교가 성립하지 않는다. **"다시 쓴 것"과 "버린 것"을 결정론으로 구별할 방법이 없다** — 그래서 검사를 정교화하는 대신 권한을 뺐다(합치기는 모호함 없는 연산이라 불변 원칙 4가 그대로 적용된다). 대가: 표현이 다른 중복이 각각 남는다(요약 품질 &lt; 사실 보존) | active |
| 35 | 2026-08-23 | exception | **격하(fallback)의 catch 범위는 전용 예외 타입으로 좁힌다** — `CardUnreadableException` 신설, 전략 조회는 try 밖 | `readOrNull` 이 `RuntimeException` 을 잡아 "이 행의 JSON이 깨졌다"(그 한 건)와 "이 유형에 전략이 등록되지 않았다"(그 유형 전부)를 같은 신호로 만들었다. 그 결과 재색인이 그 유형 임베딩을 전부 지우고도 잡을 `READY` 로 마쳤고(벡터 채널은 정상 표시), 조회는 근거가 있는데 "기록 없음"을, 승인은 사용자가 고칠 수 없는 상황을 409로 냈다. 배선 결함은 격하 대상이 아니라 500으로 드러날 결함 (→ `java-spring.md` §3) | active |
| 36 | 2026-08-23 | store | 트러블슈팅 결말 발화 판정에 **`failed`·`partial` 시도도 포함**한다(전엔 `worked` 만) | "통한 시도"만 발화로 보면 **후퇴가 앞의 성공을 뒤집지 못한다**: 앞 조각 RESOLVED + 해결책, 뒤 조각 "그걸로 배포했는데 다시 터졌다"(기본값 UNRESOLVED + `failed` 시도)여도 앞의 RESOLVED 가 저장돼, 고쳤다가 다시 깨진 대화가 해결됨으로 남는다 — Decision 13이 가장 위험하다고 지목한 방향이다. `unknown` 은 제외(판정하지 못했다는 표시) | active |
| 37 | 2026-08-23 | exception | 커밋된 응답 검사를 **응답을 만드는 공통 통로**에 둔다(개별 핸들러 아님) | 조건 없는 규칙을 핸들러마다 손으로 지키니 async 타임아웃에만 들어가고 catch-all 에는 빠졌다 — 스트리밍 중 터진 비-타임아웃 예외가 이미 커밋된 응답에 500 본문을 쓰려 했고, 그 시도가 다시 실패해 로그에서 원인이 가려졌다. 한 통로에서 막으면 규칙이 구조적으로 성립한다 | active |
| 38 | 2026-08-23 | memory | `memory.status` 어휘를 **쿼리에서도 상수로** 쓴다(`MemoryStatus.ACTIVE`, JPQL·native SQL 3곳) | 상수 홀더를 만들고도 쿼리 3곳이 리터럴로 남아 있었다(홀더 자신의 javadoc 이 그 자리를 문제로 지목한 상태였다). 쿼리 문자열은 컴파일러가 검사하지 않으므로 값을 바꾸면 컴파일은 통과하고 **검색·재색인 결과가 조용히 빈다**. `ACTIVE` 는 컴파일 상수라 `@Query` 애노테이션 값에서도 연결된다 | active |
| 39 | 2026-08-23 | 구조 | 검색 인덱스(`memory_embedding`·`search_tsv`)의 **소유권을 search 로** 옮긴다: 저장소는 `search/repository`, 색인 절차는 `search/SearchIndex`(공개 계약) | 저장소가 memory 에 있는 동안 search·store·review 가 각자 직접 잡았고, **색인 절차가 두 곳에 복제**됐다(승인 경로와 재색인 경로가 각자 `SearchRepresentation` 을 조회해 임베딩 저장). 복제의 대가는 규약이 갈라지는 것 — "색인 텍스트에 무엇을 넣는가"를 한쪽만 바꾸면 같은 카드가 승인 직후와 재색인 이후에 다르게 검색된다. 인덱스를 소유한 모듈이 절차도 소유하면 그 갈라짐이 구조적으로 불가능해진다 | active |
| 40 | 2026-08-23 | 구조 | 모듈 간 계약을 **공개 창구 + record** 로 고정: `memory/MemoryAccess`+`StoredMemory` · `capture/CaptureAccess` · `review/ReviewIntake`. 엔티티·리포지토리를 모듈 밖으로 내보내지 않는다 | 32번이 드러낸 침범 34건을 실제로 없앤 작업. 제일 무거운 건 승인 경로였다: `ReviewService` 가 `new Memory(...)` 로 행을 만들어 직접 저장해 **memory 의 저장 규약이 두 곳에 존재**했고, 그 상태에서 memory 에 불변식을 추가하면 승인으로 만들어진 행만 규약을 벗어난 채 쌓인다. store 는 `ReviewItem` 을 직접 만들어 저장해 **승인 게이트의 입구를 store 가 소유**하고 있었고, `capture.setStatus(DONE)` 으로 남의 상태 기계를 직접 돌렸다. 34 → **6건**(전부 JPA FK 연관) | active |
| 41 | 2026-08-23 | 구조 | 어휘 상수(`MemoryStatus`·`EmbeddingStatus`)와 다른 모듈이 부르는 서비스(`SettingsService`·`HybridSearchService`)는 **모듈 root** 에 둔다 | "root = 공개 계약" 규칙(32번)을 코드가 실제로 만족하게 만든 정리다. 상태 어휘는 엔티티가 아니라 **공개 값**이고(search 의 SQL 이 `MemoryStatus.ACTIVE` 를 쓴다), 다른 모듈이 import 하는 서비스는 정의상 공개 계약이다. `service/` 안에 두면 "남의 내부를 import 하는 줄"과 구별되지 않아 규칙이 자기 검증력을 잃는다 | active |
| 42 | 2026-08-23 | 구조 | ~~남는 침범 **6건은 JPA FK 연관**으로 인정한다~~: `Memory→Capture` · `ReviewItem→Capture,Memory` 와 그 연관을 세우는 `MemoryAccess`·`ReviewIntake` 의 `getReference` | 이 6건은 스키마의 FK 그 자체다 — 끊으려면 `@ManyToOne` 을 plain FK 컬럼으로 내리고 (마이그레이션은 없지만) 연관을 쓰는 코드·테스트를 함께 바꿔야 한다. 소유자 파생(`user_id` 는 capture 에서만 온다 — 교차유출 금지)을 지키려면 memory 가 capture 를 읽어야 하므로, 연관을 소유한 모듈이 참조 클래스를 아는 것은 회피가 아니라 사실이다. 후속으로 남긴다 | open |
| 43 | 2026-08-23 | 구조 | 모듈 경계를 넘는 FK 는 **연관이 아니라 id 컬럼**으로 맵핑한다(`Memory.captureId` · `ReviewItem.captureId`·`memoryId`). 42번 대체 — 침범 6 → **0건** | 42번에서 "FK 연관은 어쩔 수 없다"고 인정했지만 다시 보니 **스키마 변경이 아니라 매핑 변경**이었다(같은 컬럼, 마이그레이션 없음). 연관으로 두면 그 모듈이 남의 엔티티 클래스를 알아야 하고 그 지식이 행을 만드는 서비스까지 번진다(참조를 얻어야 하므로). 필요한 건 FK 값 하나이고 무결성은 DB 제약이 지킨다. 부수 효과로 lazy 프록시·연관 탐색이 사라져 트랜잭션 밖 접근 사고도 없어졌다 | active |
| 44 | 2026-08-23 | 보안 | 연관이 **타입으로 강제하던 불변식**(`memory.user_id` = capture 소유자)은 유일한 쓰기 경로가 책임지고, 회귀 테스트가 타입의 자리를 대신한다(`MemoryAccessOwnerDerivationTest`, release-gate) | 43번의 대가다: 생성자가 `Capture` 를 받던 동안은 소유자 파생이 컴파일러가 지키는 사실이었는데, `long` 으로 낮추면 아무 값이나 들어갈 수 있다. 그 강제를 잃은 채 두면 남의 원문으로 만든 카드가 내 소유로 저장될 수 있다(🔴 교차유출). 그래서 `MemoryAccess`·`ReviewIntake` 가 호출자의 값을 믿지 않고 `CaptureAccess.ownerOf` 로 직접 파생하고, 그 동작을 테스트로 고정한다 | active |
| 45 | 2026-08-26 | capture | 원문 캡처는 동기 DB 커밋 유지(redis 버퍼/배치 도입 안 함) | 유실 금지 앵커(불변 원칙) — 버퍼 flush 전 크래시 = 근거 유실. 캡처는 write당 INSERT 1회라 현재 병목 아님. 부하 병목 확인 시 유실 보장되는 방식으로 재검토(PR#4 리뷰 코멘트) | active |

## 4. 개인용 설정 (`templates/`)

| 템플릿 | 복사 위치 | 용도 |
|--------|-----------|------|
| `templates/user-global-CLAUDE.md` | `~/.claude/CLAUDE.md` | 전역 개인 취향(응답 언어 등), 모든 프로젝트 적용 |
| `templates/CLAUDE.local.md` | 루트 `CLAUDE.local.md` | 이 프로젝트 개인 메모(gitignore 됨) |
| `templates/settings.local.json` | `.claude/settings.local.json` | 개인 권한 오버라이드(공유 settings.json 을 덮어씀) |

팀 전체 규칙이면 개인 파일이 아니라 **공유 CLAUDE.md / settings.json** 에 넣고 PR 한다.
