# Recall v1 — 리뷰 피드백 답변 (설계 결정 방어)

> v1 스캐폴드에 대한 코드 리뷰 질문에 대한 답변. 모두 실제 코드 기준.
> 관련 문서: [DEVELOPMENT_v1.md](./DEVELOPMENT_v1.md)

---

## DB

### 1. index (어떤 인덱스를, 왜)
`V1__init.sql`에 3종류의 인덱스가 목적별로 걸려 있다.

| 인덱스 | 대상 | 종류 | 이유 |
|--------|------|------|------|
| `idx_memory_*_embedding` (4개) | 임베딩 4컬럼 | **HNSW** (`vector_cosine_ops`) | 벡터 근사최근접(ANN) 검색. 코사인 거리로 의미 유사도 정렬 |
| `idx_memory_search_tsv` | `search_tsv` | **GIN** | 전문검색(tsvector) 역색인. 키워드 매칭 |
| `idx_memory_project`, `idx_memory_type_status`, `idx_review_queue_status` | 일반 컬럼 | **B-tree** | 필터링/조회 조건 (프로젝트별, 상태별) |

핵심은 **인덱스 종류를 쿼리 패턴에 맞췄다**는 점. 벡터 유사도는 B-tree로 못 하니 HNSW, 전문검색은 GIN, 단순 등치/범위 필터는 B-tree. 하이브리드 검색(exact+BM25+vector 병렬)이 이 인덱스들을 각각 사용한다.

- `raw_text`는 의도적으로 인덱스 없음(증거 보존용, 검색 대상 아님 — 주석에 명시).
- `memory_capture`는 복합 PK가 곧 인덱스라 별도 인덱스 불필요.

### 2. json / jsonb
전 테이블에서 **JSONB** 사용: `capture.masked_spans`, `memory.structured`, `review_queue.proposed`, `audit.detail`.

- **왜 JSON이 아니라 JSONB?** JSON은 입력 텍스트를 원문 그대로(공백·키 순서·중복키 포함) 저장하고 읽을 때마다 파싱한다. JSONB는 **파싱된 바이너리**로 저장해 쿼리가 빠르고 **GIN 인덱스**가 가능하다. 조회·검색이 목적이므로 JSONB.
- **왜 정규 컬럼이 아니라 JSONB?** `structured`는 타입에 따라 스키마가 다르다 — 트러블슈팅은 `attempts[]/root_cause/resolution`, 지식은 `facts[]/document`. 컬럼으로 펼치면 절반이 NULL인 희소 테이블이 된다. 가변 스키마 + 승인 대기 중인 "제안된 구조(proposed)"라 JSONB가 적합.
- **트레이드오프:** FK·타입 강제·NOT NULL을 DB가 못 걸어줌 → 애플리케이션 레벨(추출 시 JSON 스키마 강제)에서 보장.

### 3. tsvector vs vector(1024)
**경쟁 관계가 아니라 상호 보완.**

| | `tsvector` (GIN) | `vector(1024)` (HNSW) |
|---|---|---|
| 검색 방식 | 어휘/키워드 (lexical) | 의미/밀집벡터 (semantic) |
| 매칭 | 토큰 일치 (에러코드, 함수명, 고유명사) | 뜻이 비슷한 것 (패러프레이즈) |
| 기술 | PostgreSQL 내장 FTS | pgvector 임베딩 |

`tsvector`는 `NullPointerException` 같은 **정확한 토큰**을 잘 찾고, `vector`는 "널 터졌을 때"처럼 **표현이 달라도 의미가 같은 것**을 찾는다. 설계상 하이브리드 검색(exact + BM25 + pgvector 병렬 → Weighted RRF로 융합)에서 **둘 다** 사용한다. 하나로는 반쪽짜리.

- 임베딩을 문제/해결 2개, 사실/문서 2개로 **분리**한 이유: 트러블슈팅에서 "증상"과 "해결책"을 각각 검색해야 함(설계문서 3.4).

### 4. PK → Auto Increment?
현재 `BIGINT GENERATED ALWAYS AS IDENTITY` (= SQL 표준 identity, JPA `GenerationType.IDENTITY`). 즉 **auto increment 맞다.**

- **왜 UUID가 아니라 identity?** **단일 사용자 셀프호스트**(user 테이블도 없음). 분산 쓰기·DB 병합·ID 추측 방지(enumeration) 요구가 없다. 그런 요구가 없으면 identity가 더 낫다 — 단조 증가라 인덱스 지역성이 좋고(랜덤 UUID는 B-tree 페이지가 흩어짐), 8바이트로 작고, 사람이 읽기 쉽다.
- **`GENERATED ALWAYS`**: 애플리케이션이 id를 수동으로 못 넣게 강제(실수 방지). `BY DEFAULT`보다 안전.
- **`memory_capture`는 다름**: 대리키 대신 `(memory_id, capture_id)` **복합 자연키**. 조인 테이블의 정석(중복 링크 방지 + 인덱스 겸용).

### 5. memory 테이블 `confidence REAL` — 무슨 타입, 왜?
- **`REAL`** = PostgreSQL 4바이트 단정밀도 부동소수(유효숫자 ~6자리). Java 엔티티에선 `Float`(`Memory.java:43`).
- **용도**: LLM 추출/판정이 매기는 **0..1 신뢰도 점수**. 낮은 신뢰도 추출을 검토에서 강조하거나 검색 랭킹 보정에 사용.
- **왜 `NUMERIC`/`DECIMAL`이 아니라 `REAL`?** confidence는 돈이 아니라 **근사 점수**라 정밀도가 무의미. `NUMERIC`은 임의정밀도라 정확하지만 느리고 큼. 대략적 스코어엔 `REAL`이 작고 빠름.
- **nullable인 이유**: 스캐폴드 단계라 아직 미채움(LLM 미연동). 값이 없을 수 있는 선택적 메타데이터.

---

## Backend

### 6. Actuator가 뭐고, 왜 health, info만?
- **Actuator** = Spring Boot 운영용 엔드포인트 모음(health, info, metrics, env, beans, mappings, heapdump 등). 모니터링·헬스체크·진단용.
- **왜 `health,info`만 노출?** (`application.yml:24-28`) **보안**. `env`·`beans`·`mappings`·`heapdump`는 환경변수·내부구조·메모리를 노출해 웹에 열면 위험. 안전한 두 개만:
  - `health`: 앱+DB 살아있는지 (로드밸런서/도커 헬스체크)
  - `info`: 빌드·버전 정보
- 나중에 Prometheus 붙이면 `metrics` 추가. 최소 노출 원칙.

### 7. `open-in-view: false`로 설정한 이유
- **OSIV(Open Session In View)** = Hibernate 영속성 컨텍스트를 **HTTP 요청 전체**(뷰 렌더링까지) 열어두는 기능. Spring 기본 `true`지만 기동 시 경고를 띄운다.
- **왜 끄나:**
  1. **DB 커넥션을 요청 내내 점유** → 부하 시 커넥션 풀 고갈. 특히 조회 경로는 **SSE 스트리밍(최대 60초)** 이라 스트림 도는 동안 커넥션 점유는 치명적.
  2. 지연로딩이 서비스 밖(컨트롤러)에서 은근슬쩍 터져 N+1·예상치 못한 쿼리 유발.
  3. `false`면 **필요한 데이터를 `@Transactional` 서비스 안에서 명시적으로 로딩**하게 강제 → 트랜잭션 경계가 깔끔.
- 정리: 커넥션 수명을 트랜잭션 안으로 가두려는 의도. SSE 아키텍처와 특히 잘 맞음.

### 8. Flyway를 쓴 이유 → `ddl-auto: none` 이유
- **Flyway** = 버전 관리되는 SQL 마이그레이션(`V1__init.sql`). **`ddl-auto: none`** = Hibernate가 스키마를 건드리지 않음.
- **둘은 한 세트** — "스키마의 소유자는 Flyway, JPA는 매핑만."
- **왜 Hibernate `ddl-auto`(update/create)로 안 하나:**
  - `update`는 컬럼 삭제를 못 하고, 실행 순서 불명확, 운영에서 위험.
  - 이 스키마엔 Hibernate가 **생성할 수 없는 것**투성이: `CREATE EXTENSION vector`, `vector(1024)` 타입, **HNSW/GIN 인덱스**, `tsvector`, `GENERATED ALWAYS AS IDENTITY`. 손으로 쓴 DDL이어야 함.
  - Flyway는 **재현 가능·리뷰 가능·버전 관리되는** 이력을 남김(운영 배포 필수).
- Flyway가 정확한 DDL을 소유, JPA는 런타임 매핑만(`ddl-auto: none`이라 검증조차 안 하고 신뢰). 충돌 방지.

### 9. `@EnableAsync`란?
- Spring **비동기 실행 활성화** 스위치(`RecallApplication.java:16`). 이게 있어야 `@Async` 메서드가 **별도 스레드풀**에서 돌고 호출자는 즉시 리턴.
- **왜 필요:** `ExtractionService.extract()`가 `@Async`(`ExtractionService.java:32`). 저장 경로의 무거운 LLM Map-Reduce 추출을 백그라운드로 돌려서 `CaptureController`는 기다리지 않고 **즉시 `202 Accepted`** 반환(`CaptureController.java:26`).
- 아키텍처 원칙: **저장=비동기 잡 / 조회=동기 SSE**. `@EnableAsync`가 없으면 `@Async`는 무시되고 동기 실행됨.

### 10. `@Autowired` 안 쓴 이유, Lombok
- **생성자 주입** 사용 (`final` 필드 + 생성자, 예: `CaptureService.java:16-29`). 필드 `@Autowired` 안 씀.
  1. **불변성**: `final`로 주입 후 못 바꿈.
  2. **테스트 용이**: 컨테이너·리플렉션 없이 `new`로 목 넣어 생성.
  3. **필수 의존성 명시** + 순환참조 조기 발견.
  4. **생성자 하나면 `@Autowired` 생략 가능** — Spring 공식 권장이 생성자 주입.
- **Lombok 미사용**: 게터·생성자 직접 작성. 애노테이션 프로세서/빌드 매직 의존 제거로 **투명성** 확보(학습 프로젝트라 코드가 그대로 보이는 게 이점). DTO는 **record**로 보일러플레이트 대체.
- 트레이드오프: 코드가 길어짐. 하지만 record + 명시적 코드로 의존성 최소·디버깅 친화 확보.

### 11. `LlmClient.complete()`에서 바로 `throw`하는 이유 (질문의 "compose")
`throw`하는 유일한 곳은 `LlmClient.complete()` / `embed()`의 `throw new UnsupportedOperationException(...)`(`LlmClient.java:23,29`).

- **의도된 fail-fast 스텁.** 미구현 AI 로직을 호출하면 **빈 값/가짜 값을 조용히 반환하지 않고** 명확히 터뜨린다.
- `ApiExceptionHandler`가 잡아 **`501 Not Implemented`** + 메시지로 변환(`ApiExceptionHandler.java:12-15`). "미구현"이 API 응답에 정직하게 드러남.
- **"근거 없는 답변 금지"** 원칙과도 부합 — 스텁이 그럴듯한 가짜 답을 내놓아 진짜처럼 보이는 사고 방지.

### 12. `SseEmitter`를 선택한 이유 / 사용처 / Spring MVC만 쓴 이유
- **어디서 쓰나:** 조회 경로. `QueryController.query()`가 `SseEmitter` 반환(`QueryController.java:23`, `produces = text/event-stream`) → `SearchService.answer()`가 emitter 생성(60초 타임아웃)하고 `분류→플래너→검색→AnswerComposer` 실행(`SearchService.java:33-45`) → `AnswerComposer.compose()`가 `emitter.send(event "answer"/"done")`으로 **스트리밍**(`AnswerComposer.java:21-31`).
- **왜 SSE인가:** LLM 답변은 **토큰 단위로 흘려보내야** UX가 좋음. 서버→클라 **단방향** 스트림이면 충분하므로 양방향 WebSocket은 과함. SSE는 HTTP 위라 프록시·재연결이 단순.
- **왜 Spring MVC(WebFlux 아님)?** 조회 경로는 LLM 호출이 **딱 2번**(의도분류 + 답변재구성)이라 동기 블로킹으로 충분(`SearchService` 주석에 명시). WebFlux는 러닝커브·디버깅 비용이 큰데 단일 사용자엔 동시성 이득 미미. **MVC + SseEmitter**면 리액티브 없이 스트리밍 가능.
- **개선점(지적 타당):** 현재 `SearchService.answer()`는 `SseEmitter`를 만들고 **호출 스레드에서 바로** compose까지 다 돌린 뒤 리턴한다. 실제 LLM을 붙이면 이 동기 호출이 요청 스레드를 오래 잡는다 → **별도 스레드에서 send**하도록(emitter만 먼저 반환 후 `@Async`/executor에 태워 처리) 리팩터링 필요. 지금은 스텁이라 동작하지만 실제 연동 시 개선 대상.

### 13. Controller response 공통화
- **현재:** 공통 성공 래퍼 **없음.** capture는 `ResponseEntity<CaptureResponse>`(202), query는 `SseEmitter`, review/memory는 각자 DTO. 에러만 `ApiExceptionHandler`가 **`ProblemDetail`**(RFC 7807 표준)로 공통화.
- **판단:** 성공 응답을 `ApiResponse<T>{data, error}`로 감싸는 방식도 있으나 여기선 **의도적으로 안 한 게 맞다**:
  - SSE(`SseEmitter`)는 공통 바디로 못 감쌈.
  - 에러는 이미 `ProblemDetail` 표준으로 일관.
  - HTTP 상태코드(202/200/501)를 의미 전달에 쓰는 게 REST 정석.
- "성공은 상태코드+각 DTO, 에러는 ProblemDetail 표준"으로 이미 일관성 있음. 별도 성공 래퍼는 팀 컨벤션 문제.

### 14. Service impl(인터페이스+구현 분리)을 안 쓴 이유
- `CaptureService`를 `interface + CaptureServiceImpl`로 안 나누고 **클래스 하나**로 둠.
- **이유:** 구현체가 **하나뿐**인데 인터페이스는 불필요한 간접층. `interface+Impl` 패턴은 (1) 구현이 여럿이거나 (2) 스프링 AOP 프록시 때문에 인터페이스가 필요하던 **옛날 관행**인데, 지금은 CGLIB 프록시로 클래스도 프록시가 되므로 불필요.
- YAGNI — 실제 다형성이 필요해질 때 추출해도 늦지 않음. 학습/스캐폴드 단계엔 클래스 직접이 더 읽기 쉬움.

### 15. 파일 구조는 뭘 가져다 쓴 건지
- **패키지 by feature(도메인/모듈별)** 구조. `com.recall.{capture, intent, extraction, typerouter, review, memory, search, llm, common}` — 각 모듈이 자기 Controller/Service/Repository/Entity/dto를 품음.
- **"모듈러 모놀리스"** 스타일: 계층별(controller/service/repository 폴더로 전부 모으는 layer-by-layer)이 아니라 **기능별로 응집**. 한 기능 수정 시 한 패키지만 보면 되고, 모듈 경계가 명확해 나중에 서비스 분리도 쉬움. DDD 바운디드 컨텍스트를 가볍게 반영.
- dto는 각 모듈 하위 `dto/`. 공통 관심사(예외처리·감사로그·CORS)는 `common/`.

### 16. CORS를 설정한 이유
- `WebConfig`(`WebConfig.java`)에서 `/api/**`에 `http://localhost:3000`만 허용.
- **왜:** **개발 환경 전용**(주석에 명시). Vite 개발서버 `:3000`, 백엔드 `:8080` — **다른 origin**이라 브라우저 동일출처정책(SOP)에 막힘. 개발 중 프론트가 API를 직접 호출하려면 CORS 허용 필요.
- **운영에선 불필요:** nginx가 SPA와 `/api`를 **같은 origin**으로 서빙(리버스 프록시)하므로 CORS 자체가 발생 안 함. origin을 localhost:3000으로 **좁게** 못박아 운영에 영향 없음. 와일드카드(`*`) 안 쓴 것도 잘한 부분.

### 17. Exception handler란?
- `@RestControllerAdvice` + `@ExceptionHandler`로 만든 **전역 예외 처리기**(`ApiExceptionHandler.java`). 컨트롤러들에서 던져진 예외를 **한 곳에서** 잡아 HTTP 응답으로 변환.
- 현재: `UnsupportedOperationException`(미구현 스텁) → **`501` + `ProblemDetail`**. 각 컨트롤러에 try-catch를 흩뿌리지 않고 중앙집중 처리 → 응답 형식 일관(RFC 7807).
- 확장 지점: 검증실패(`MethodArgumentNotValidException`)→400, 없는 리소스→404 등을 여기에 추가.

### 18. ENUM을 사용하는 이유
- `MemoryType{TROUBLESHOOTING, KNOWLEDGE}`, `IntentType`, `QueryIntent`, `Judgement` 등.
- **이유:** 값의 **집합이 고정**되고 컴파일 타임에 알려짐. enum이면:
  - **타입 안전** — 오타/잘못된 문자열이 컴파일 에러로 잡힘.
  - **가독성/자동완성**, `switch` 망라성 검사.
  - DB엔 `@Enumerated(EnumType.STRING)`(`Memory.java:26`)로 **문자열** 저장 — `ORDINAL`(숫자)은 enum 순서 바뀌면 데이터가 깨지므로 `STRING`이 안전(잘함).
- 상태값을 `String`으로 두면 오타·유효성 문제가 생기는데 enum이 막아줌.

### 19. record vs class 차이
- **record**(DTO: `CaptureRequest`, `CaptureResponse`, `ReviewItem`, `QueryRequest` 등)
  - **불변 데이터 캐리어**. 생성자·게터·`equals`/`hashCode`/`toString` **자동 생성**. 필드 `final`.
  - `CaptureRequest`(`CaptureRequest.java`)처럼 요청/응답 값 묶음에 적합 — 간결, `@NotBlank` 검증도 붙고, 커스텀 메서드(`sourceTypeOrDefault()`)도 추가 가능.
- **class**(엔티티: `Capture`, `Memory`)
  - **가변 + 정체성(identity)** 이 필요한 경우. JPA 엔티티는 **가변**(setter로 상태 변경), 프록시/지연로딩 위해 **인자 없는 생성자** 필요(`protected Memory(){}`), 식별자는 값이 아니라 `id`로 판단 → **record로 만들 수 없음**.
- **기준:** "불변 값 묶음" = record(DTO), "가변 상태 + JPA 관리" = class(엔티티). 이 프로젝트가 정확히 그 기준으로 나눔.

---

## Frontend

### 20. nginx가 front 하위에 있는 이유
- `frontend/nginx.conf` 위치.
- **이유:** 이 nginx 설정은 **프론트엔드 서빙용**이라 프론트와 함께 둠. 하는 일 두 가지(`nginx.conf`):
  1. `location /` → `npm run build` 결과물(`frontend/dist`)을 정적 서빙 + SPA fallback(`try_files ... /index.html`).
  2. `location /api/` → 백엔드(`:8080`)로 프록시 (`proxy_buffering off`로 **SSE 스트리밍 유지**).
- "프론트 빌드물을 어떻게 서빙하고 API로 넘기는가"에 대한 **프론트 배포 설정**이라 `frontend/` 밑이 자연스럽다.
- **개선점(지적 타당):** 주석엔 "nginx가 호스트에서 직접 실행(도커 아님)"이라 되어 있다. 그럼 이 파일은 프론트만의 것이 아니라 **시스템 전체 리버스 프록시** 성격이라 `infra/`·`deploy/` 같은 배포 전용 디렉터리로 빼는 게 더 맞다는 의견도 타당. 지금은 "프론트 서빙이 주목적"이라 frontend 밑에 둔 것이고, 커지면 이동 고려.

---

## 총평 (개선 여지 인정)

대부분 **의도가 분명한 선택**이라 방어 가능. 리뷰 지적 중 실제 개선 여지:

1. **`SearchService.answer()`의 SSE 처리** — 실제 LLM 연동 시 요청 스레드에서 동기로 다 돌리면 안 되고, emitter만 먼저 반환 후 별도 스레드에서 send하도록 리팩터링 필요. (질문 12)
2. **nginx.conf 위치** — 호스트 전역 프록시라면 `infra/`로 빼는 게 더 정확. (질문 20)

나머지(identity PK, JSONB, tsvector+vector 병행, open-in-view:false, Flyway+ddl-none, 생성자주입, enum STRING, record/class 분리)는 **모범적 선택**이라 그대로 방어 가능.
