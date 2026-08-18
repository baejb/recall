# 멀티유저 사용자별 AI 설정(키·모델) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 각 사용자가 자기 LLM/임베딩 provider·키를 소유(BYO)하고, "이 호출이 누구 키를 쓰나"를 파이프라인 전 구간에서 명시적 인자(UserAiContext)로 강제해 교차유출을 구조적으로 막는다.

**Architecture:** 진입점(조회 요청·저장 @Async 잡·승인·재색인 루프)에서 신뢰된 소유자 id 로 `AiContextFactory.forUser(userId)` 를 호출해 그 사용자 설정으로 바인딩된 불변 `UserAiContext{userId, llm, embedding, chatReady, embeddingReady}` 를 만들고, LLM/임베딩을 쓰는 모든 계층에 이 컨텍스트를 인자로 넘긴다(방식 B, ThreadLocal 미사용). 미설정 사용자는 도메인 예외로 차단(env 폴백은 부트스트랩 전용). 설정·상태·재색인은 사용자별.

**Tech Stack:** Spring Boot 4.0 / Java 25 · PostgreSQL 17 + pgvector · Flyway · JUnit 5 + Mockito · Gradle.

**Spec:** `docs/design/multiuser-per-user-ai-settings.md`

## Global Constraints

- 스키마 변경은 **Flyway 새 마이그레이션(V12+)** 으로만. 기존 V1~V11 수정 금지.
- provider 키는 **at-rest AES-GCM 암호화** 유지(`SecretCipher`). 평문 저장·로그·`toString`·클라이언트 반환 금지.
- **교차유출 금지**: 설정 조회/변경/키 사용/재색인 모두 소유자 스코프. userId 는 신뢰 출처(CurrentUserProvider / DB의 capture.user_id / DB 순회)에서만 — **HTTP 요청 입력 userId 금지**.
- 결정론 단계(P/R/W)에 LLM 금지. 이 변경은 provider 해석만 사용자별로 바꾼다.
- **테스트 우선(red→green)**. 🔴 교차유출 케이스는 `@Tag("release-gate")`.
- 포맷: `./gradlew spotlessApply`. import 상단 선언(본문 FQN 금지). 생성자 주입만, 주입 필드 final.
- 테스트 DB: `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5442/<db>` (dev DB 포트 5442). 새 DB로 격리 실행 후 삭제.
- 미설정 차단 예외: 신규 도메인 예외 `AiNotConfiguredException`(§Task 5) → `ApiExceptionHandler` 에서 **409 Conflict** 매핑. 저장 경로는 이를 잡아 `failed_stage=context`.

---

## File Structure

- `db/migration/V12__model_setting_user_id.sql` — model_setting 사용자별 전환 + fail-loud 귀속 (Create)
- `settings/ModelSetting.java` — user_id 필드 (Modify)
- `settings/ModelSettingRepository.java` — findByUserId, 사용자별 generation UPDATE (Modify)
- `common/AiNotConfiguredException.java` — 미설정 차단 도메인 예외 (Create)
- `common/ApiExceptionHandler.java` — 409 매핑 (Modify)
- `settings/SettingsService.java` — 사용자별 해석·CRUD 스코프·env 부트스트랩 전용 (Modify)
- `settings/ModelSettingInitializer.java` — 부트스트랩 사용자만 시드 (Modify)
- `llm/UserAiContext.java` — 사용자별 바인딩 컨텍스트 (Create)
- `llm/AiContextFactory.java` — forUser(userId) (Create)
- `query/QueryController.java`, `query/AnswerStreamer.java`, `query/QueryPipeline.java` — 컨텍스트 관통 + capability 차단 (Modify)
- `store/StorePipeline.java`, `store/LongContextExtractor.java`, `store/SimilarMemoryFinder.java` — capture 소유자 컨텍스트 + findByIdAndUserId (Modify)
- `review/ReviewService.java` — 승인 시 forUser (Modify)
- `search/ReindexService.java`, `settings/EmbeddingModelChangedEvent.java`, `memory/MemoryRepository.java` — 사용자별 재색인 (Modify)
- 테스트: `settings/`·`store/`·`query/`·`search/` 아래 신규 격리/차단 테스트

핵심 인터페이스(전 태스크 공유 계약):

```java
// llm/UserAiContext.java — 불변. toString에 키/설정 노출 금지(record 기본 toString은 필드 나열이므로 커스텀).
public record UserAiContext(long userId, LlmClient llm, EmbeddingClient embedding,
                            boolean chatReady, boolean embeddingReady) {
    public LlmClient requireChat() {           // chat 미설정이면 차단
        if (!chatReady) throw new AiNotConfiguredException("chat 설정 필요(user=" + userId + ")");
        return llm;
    }
    public EmbeddingClient requireEmbedding() { // embedding 미설정이면 차단
        if (!embeddingReady) throw new AiNotConfiguredException("embedding 설정 필요(user=" + userId + ")");
        return embedding;
    }
    @Override public String toString() { return "UserAiContext[userId=" + userId
            + ", chatReady=" + chatReady + ", embeddingReady=" + embeddingReady + "]"; } // 키/provider 비노출
}

// llm/AiContextFactory.java
public UserAiContext forUser(long userId);   // 그 userId 설정을 읽어 바인딩. 소유권은 추론하지 않음(호출부 책임).
```

---

## Task 1: V12 — model_setting 사용자별 전환 + fail-loud 귀속

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__model_setting_user_id.sql`
- Test: `backend/src/test/java/com/recall/settings/ModelSettingUserIdMigrationTest.java`

**Interfaces:**
- Consumes: V11 의 `app_user`(id=1 부트스트랩), V4~V6 의 `model_setting`(id=1 단일행 + generation/status 컬럼).
- Produces: `model_setting.user_id`(NOT NULL, FK→app_user, UNIQUE). 부트스트랩 행은 user_id=1.

- [ ] **Step 1: 실패 테스트 작성** — 마이그레이션 후 스키마 계약 검증.

```java
// ModelSettingUserIdMigrationTest.java
@SpringBootTest
class ModelSettingUserIdMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V12: model_setting에 user_id NOT NULL UNIQUE FK, 부트스트랩 행은 user_id=1")
    void migratesToPerUser() {
        Integer col = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
          + "WHERE table_name='model_setting' AND column_name='user_id' AND is_nullable='NO'",
            Integer.class);
        assertEquals(1, col, "user_id NOT NULL 컬럼 존재");
        Long owner = jdbc.queryForObject(
            "SELECT user_id FROM model_setting WHERE id=1", Long.class);
        assertEquals(1L, owner, "기존 단일행은 부트스트랩(1)로 귀속");
        Integer uq = jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conname='uq_model_setting_user'", Integer.class);
        assertEquals(1, uq, "UNIQUE(user_id) 제약 존재");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.settings.ModelSettingUserIdMigrationTest'` (SPRING_DATASOURCE_URL 새 DB). Expected: FAIL (user_id 컬럼 없음).

- [ ] **Step 3: 마이그레이션 작성** — 스펙 §5.1 그대로.

```sql
-- V12__model_setting_user_id.sql
-- model_setting 사용자별 전환. 단일행(id=1) 전제 검증 후 부트스트랩(1) 귀속, 위반 시 fail-loud.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = 1) THEN
    RAISE EXCEPTION '부트스트랩 사용자(app_user.id=1) 없음 — V11 시드 누락';
  END IF;
  IF (SELECT count(*) FROM model_setting) > 1
     OR EXISTS (SELECT 1 FROM model_setting WHERE id <> 1) THEN
    RAISE EXCEPTION 'model_setting 단일행(id=1) 전제 위반 — 소유자 자동 귀속 불가, 수동 마이그레이션 필요';
  END IF;
END $$;
ALTER TABLE model_setting ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE RESTRICT;
UPDATE model_setting SET user_id = 1 WHERE id = 1;
ALTER TABLE model_setting ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE model_setting ADD CONSTRAINT uq_model_setting_user UNIQUE (user_id);
```

- [ ] **Step 4: 통과 확인** — Run 동일 테스트. Expected: PASS.

- [ ] **Step 5: 커밋** — `git add backend/src/main/resources/db/migration/V12__model_setting_user_id.sql backend/src/test/java/com/recall/settings/ModelSettingUserIdMigrationTest.java && git commit -m "feat(backend): V12 — model_setting 사용자별 전환 + fail-loud 귀속"`

---

## Task 2: ModelSetting 엔티티 + 리포지토리 사용자별화

**Files:**
- Modify: `backend/src/main/java/com/recall/settings/ModelSetting.java` (user_id 필드 + getter)
- Modify: `backend/src/main/java/com/recall/settings/ModelSettingRepository.java`
- Test: `backend/src/test/java/com/recall/settings/ModelSettingRepositoryTest.java` (기존 갱신)

**Interfaces:**
- Produces: `ModelSettingRepository.findByUserId(long userId): Optional<ModelSetting>`,
  `updateEmbeddingStatusIfGeneration(long userId, String status, long generation): int`.
- Consumes: Task 1 스키마.

- [ ] **Step 1: 실패 테스트** — 사용자별 조회 + 사용자별 세대 펜싱.

```java
// ModelSettingRepositoryTest.java (추가)
@Test
@DisplayName("findByUserId로 부트스트랩(1) 설정 조회")
void findsByUserId() {
    assertTrue(repository.findByUserId(1L).isPresent());
}
@Test
@DisplayName("세대 펜싱은 user_id로 스코프 — 다른 사용자 세대는 안 건드림")
void generationFencingScopedByUser() {
    // 부트스트랩(1) 현재 세대로만 갱신되고, 존재하지 않는 user 2 조건은 0행.
    long gen = repository.findByUserId(1L).orElseThrow().getEmbeddingGeneration();
    assertEquals(1, repository.updateEmbeddingStatusIfGeneration(1L, "READY", gen));
    assertEquals(0, repository.updateEmbeddingStatusIfGeneration(2L, "READY", gen));
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.settings.ModelSettingRepositoryTest'`. Expected: FAIL (findByUserId 없음, UPDATE 시그니처 불일치).

- [ ] **Step 3: 구현** — 엔티티에 user_id, 리포지토리 시그니처를 user_id 조건으로.

```java
// ModelSetting.java: 필드 추가
@Column(name = "user_id", nullable = false, updatable = false)
private Long userId;
public Long getUserId() { return userId; }

// ModelSettingRepository.java: 전역 id=1 → user_id 스코프
Optional<ModelSetting> findByUserId(long userId);

@Transactional @Modifying
@Query("UPDATE ModelSetting m SET m.embeddingStatus = :status "
     + "WHERE m.userId = :userId AND m.embeddingGeneration = :generation")
int updateEmbeddingStatusIfGeneration(@Param("userId") long userId,
        @Param("status") String status, @Param("generation") long generation);
```

- [ ] **Step 4: 통과 확인** — Run 동일. Expected: PASS. (기존 `updateEmbeddingStatusIfGeneration(status,gen)` 호출부는 Task 9에서 수정 — 이 단계까지는 ReindexService 컴파일 깨질 수 있으니 Task 9와 함께 green 만든다. 순서상 Task 2 테스트만 격리 실행.)

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): model_setting 사용자별 조회·세대 펜싱 스코프"`

> 주의: Task 2는 ReindexService/SettingsService 호출부(전역 id=1)를 깨뜨린다. Task 3·9에서 정합을 맞춘 뒤 전체 그린을 만든다. 커밋은 논리 단위이되, 전체 빌드 그린은 Task 9 완료 시점 기준.

---

## Task 3: SettingsService 사용자별 해석 + CRUD 스코프 + env 부트스트랩 전용

**Files:**
- Modify: `backend/src/main/java/com/recall/settings/SettingsService.java`
- Test: `backend/src/test/java/com/recall/settings/SettingsServiceUserScopeTest.java` (Create)

**Interfaces:**
- Consumes: `CurrentUserProvider`(이미 존재), Task 2 `findByUserId`, `BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID`.
- Produces:
  - `EmbeddingProperties embeddingFor(long userId)` / `LlmProperties chatFor(long userId)` — 그 사용자 설정 해석.
  - `boolean isChatConfigured(long userId)` / `boolean isEmbeddingConfigured(long userId)`.
  - CRUD(get/update/probe)는 내부에서 `currentUser.currentUserId()` 스코프.

- [ ] **Step 1: 실패 테스트** — env 폴백은 부트스트랩만, 사용자2는 미설정.

```java
// SettingsServiceUserScopeTest.java
@SpringBootTest
class SettingsServiceUserScopeTest {
    @Autowired SettingsService settings;
    @Autowired JdbcTemplate jdbc;
    long user2;
    @BeforeEach void seed() { user2 = jdbc.queryForObject(
        "INSERT INTO app_user(provider,subject) VALUES('test','s2') RETURNING id", Long.class); }
    @AfterEach void clean() { jdbc.update("DELETE FROM model_setting WHERE user_id=?", user2);
                              jdbc.update("DELETE FROM app_user WHERE id=?", user2); }

    @Test @DisplayName("env 폴백은 부트스트랩(1)만 — 미설정 사용자2는 chat/embedding 미설정")
    void envFallbackBootstrapOnly() {
        assertFalse(settings.isChatConfigured(user2), "사용자2는 env 키가 있어도 미설정");
        assertFalse(settings.isEmbeddingConfigured(user2));
        // 부트스트랩은 env 시드로 최소 embedding 설정됨(테스트 환경 계약에 맞춰 조정)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.settings.SettingsServiceUserScopeTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `row()`→`row(userId)`, env 폴백을 부트스트랩 분기로 격리.

```java
// SettingsService.java 핵심 변경
private ModelSetting row(long userId) {
    return repository.findByUserId(userId)
        .orElseThrow(() -> new IllegalStateException("model_setting 미초기화(user=" + userId + ")"));
}
// env 폴백: 부트스트랩 사용자만. 그 외는 DB 암호문 없으면 미설정.
private String resolveChatKey(long userId, ModelSetting s) {
    String enc = s.getChatApiKeyEnc();
    if (enc != null && !enc.isBlank()) return cipher.decrypt(enc);
    return userId == BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID ? envChat.apiKey() : null; // 폴백은 부트스트랩만
}
public boolean isChatConfigured(long userId) { return resolveChatKey(userId, row(userId)) != null; }
// embeddingFor/chatFor: 위 resolve로 키·provider·model·base-url을 담은 Properties 반환. 미설정이면 null 키.
// CRUD get/update/probe: 파라미터 없는 진입은 currentUser.currentUserId()로 스코프.
```
> `decryptOr(enc, envFallback)` 류 일반 함수는 제거하거나 위처럼 **부트스트랩 분기 안에서만** env 를 폴백으로 쓴다(스펙 §7).

- [ ] **Step 4: 통과 확인** — Run 동일. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): SettingsService 사용자별 설정 해석 + env 부트스트랩 전용 폴백"`

---

## Task 4: ModelSettingInitializer 부트스트랩 전용 시드

**Files:**
- Modify: `backend/src/main/java/com/recall/settings/ModelSettingInitializer.java`
- Test: `backend/src/test/java/com/recall/settings/ModelSettingInitializerTest.java` (갱신)

**Interfaces:**
- Consumes: `findByUserId(1L)`, env 설정.
- Produces: 부팅 시 부트스트랩(1) 행만 env 로 시드. 다른 사용자 행 미생성.

- [ ] **Step 1: 실패 테스트**

```java
@Test @DisplayName("initializer는 부트스트랩(1) 행만 시드 — 다른 사용자 행을 만들지 않는다")
void seedsBootstrapOnly() {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM model_setting", Integer.class);
    assertEquals(1, rows, "부팅 후 model_setting은 부트스트랩 1행뿐");
    assertEquals(1L, (long) jdbc.queryForObject("SELECT user_id FROM model_setting", Long.class));
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.settings.ModelSettingInitializerTest'`. Expected: FAIL 또는 통과(현재도 1행) — 회귀 가드로 유지.

- [ ] **Step 3: 구현** — `findById(1L)`→`findByUserId(1L)`, seed 대상은 부트스트랩 고정.

```java
ModelSetting s = repository.findByUserId(BootstrapCurrentUserProvider.BOOTSTRAP_USER_ID).orElse(null);
// (없으면 부트스트랩 행 생성; 다른 사용자 행은 절대 만들지 않는다)
```

- [ ] **Step 4: 통과 확인** — Run 동일. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): initializer 부트스트랩 사용자 행만 시드"`

---

## Task 5: UserAiContext + AiContextFactory + AiNotConfiguredException

**Files:**
- Create: `backend/src/main/java/com/recall/llm/UserAiContext.java`
- Create: `backend/src/main/java/com/recall/llm/AiContextFactory.java`
- Create: `backend/src/main/java/com/recall/common/AiNotConfiguredException.java`
- Modify: `backend/src/main/java/com/recall/common/ApiExceptionHandler.java` (409 매핑)
- Test: `backend/src/test/java/com/recall/llm/AiContextFactoryTest.java` (Create)

**Interfaces:**
- Consumes: `SettingsService.chatFor/embeddingFor/isChatConfigured/isEmbeddingConfigured`, `LlmClientFactory`, `EmbeddingClientFactory`.
- Produces: `AiContextFactory.forUser(long userId): UserAiContext`(위 File Structure 계약). `AiNotConfiguredException`(RuntimeException).

- [ ] **Step 1: 실패 테스트** — 두 사용자 다른 키로 바인딩 + 미설정 차단.

```java
// AiContextFactoryTest.java
@SpringBootTest
class AiContextFactoryTest {
    @Autowired AiContextFactory factory;
    @Autowired JdbcTemplate jdbc;
    long user2;
    @BeforeEach void seed(){ user2=jdbc.queryForObject(
        "INSERT INTO app_user(provider,subject) VALUES('test','ctx2') RETURNING id",Long.class); }
    @AfterEach void clean(){ jdbc.update("DELETE FROM model_setting WHERE user_id=?",user2);
                             jdbc.update("DELETE FROM app_user WHERE id=?",user2); }

    @Test @DisplayName("미설정 사용자는 chat/embeddingReady=false, require*는 AiNotConfiguredException")
    void unconfiguredUserBlocked() {
        UserAiContext ctx = factory.forUser(user2);
        assertFalse(ctx.chatReady());
        assertThrows(AiNotConfiguredException.class, ctx::requireChat);
    }
    @Test @DisplayName("toString에 키·provider 노출 안 됨")
    void toStringHidesSecrets() {
        assertFalse(factory.forUser(1L).toString().toLowerCase().contains("key"));
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.llm.AiContextFactoryTest'`. Expected: FAIL (클래스 없음).

- [ ] **Step 3: 구현**

```java
// AiNotConfiguredException.java (common)
public class AiNotConfiguredException extends RuntimeException {
    public AiNotConfiguredException(String message) { super(message); }
}
// ApiExceptionHandler.java: 409 매핑 추가
@ExceptionHandler(AiNotConfiguredException.class)
public ProblemDetail handleAiNotConfigured(AiNotConfiguredException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage()); // 409: 설정 선행 필요
}
// AiContextFactory.java
@Component
public class AiContextFactory {
    private final SettingsService settings; private final LlmClientFactory llmFactory;
    private final EmbeddingClientFactory embFactory;
    // 생성자 주입 ...
    public UserAiContext forUser(long userId) {
        boolean chatReady = settings.isChatConfigured(userId);
        boolean embReady  = settings.isEmbeddingConfigured(userId);
        LlmClient llm = llmFactory.forSettings(settings.chatFor(userId));          // 시작 시점 고정 스냅샷
        EmbeddingClient emb = embFactory.forSettings(settings.embeddingFor(userId));
        return new UserAiContext(userId, llm, emb, chatReady, embReady);
    }
}
```

- [ ] **Step 4: 통과 확인** — Run 동일. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): UserAiContext + AiContextFactory + 미설정 차단 예외(409)"`

---

## Task 6: 조회 경로 — 컨텍스트 관통 + capability 차단(미설정≠장애)

**Files:**
- Modify: `query/QueryController.java`(forUser 해석), `query/AnswerStreamer.java`, `query/QueryPipeline.java`
- Test: `query/QueryContextIsolationTest.java` (Create), 기존 `AnswerStreamerTest`·`QueryPipelineTest` 갱신

**Interfaces:**
- Consumes: `AiContextFactory.forUser`, `CurrentUserProvider`.
- Produces: `QueryController.query()` 가 `forUser(currentUser.currentUserId())` 로 컨텍스트를 만들어 `AnswerStreamer.stream(question, ctx)` 로 전달. `QueryPipeline.classify/retrieve/rerank/compose*` 가 `UserAiContext` 를 받아 `ctx.requireChat()/requireEmbedding()` 사용.

- [ ] **Step 1: 실패 테스트** — A 요청에 B userId 넣어도 A만, chat 미설정 차단(409), 외부장애는 격하 유지.

```java
// QueryContextIsolationTest.java (요지)
@Test @DisplayName("chat 미설정 사용자의 답변 요청은 409(격하 아님)")
void unconfiguredChatBlocksAnswer() { /* forUser(user2) 컨텍스트 → answer 진입점이 requireChat → AiNotConfigured */ }
@Test @DisplayName("설정 완료 후 외부 LLM 실패는 기존 격하(BM25/요약) 유지")
void externalFailureStillDegrades() { /* chatReady=true지만 llm.complete가 예외 → 기존 fallbackFragments 경로 */ }
```
> 핵심 계약: **미설정(chatReady=false) → AiNotConfiguredException(409)**. **설정됨 + 호출 실패 → 기존 격하**. `llmReady()`(전역 available) 하나로 섞지 않는다.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.query.*'`. Expected: FAIL.

- [ ] **Step 3: 구현**
  - `QueryController.query`: `UserAiContext ctx = contextFactory.forUser(currentUser.currentUserId()); return streamer.stream(request.question(), ctx);`
  - `AnswerStreamer.stream(String q, UserAiContext ctx)` → 가상스레드 클로저에 `ctx` 캡처 → `emit(emitter, q, ctx)`.
  - `emit`: 검색은 `ctx.userId`+`ctx.requireEmbedding()`(임베딩 채널 필요 시). 답변 단계 진입 시 `ctx.requireChat()` — chat 미설정이면 여기서 409로 중단(격하 아님). 설정됨인데 호출 실패면 기존 `fallbackFragments`.
  - `QueryPipeline.classify/rerank/compose*` 시그니처에 `UserAiContext ctx`(또는 `LlmClient`) 추가, 내부에서 `ctx.requireChat()` 사용. `retrieve`는 이미 `userId` 받음 → 임베딩 검색은 `ctx.requireEmbedding()`.
  - `llmReady()`(전역) 제거, capability 는 `ctx.chatReady()`/`ctx.embeddingReady()` 로.

- [ ] **Step 4: 통과 확인** — Run 동일 + 전체 `./gradlew test`. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): 조회 경로 UserAiContext 관통 + 미설정 차단/외부장애 격하 분리"`

---

## Task 7: 저장 경로 — capture 소유자 컨텍스트 + findByIdAndUserId + failed_stage=context

**Files:**
- Modify: `store/StorePipeline.java`, `store/LongContextExtractor.java`, `store/SimilarMemoryFinder.java`
- Test: `store/StoreContextIsolationTest.java` (Create), 기존 `CaptureFailureExposureTest` 갱신

**Interfaces:**
- Consumes: `AiContextFactory.forUser(capture.getUserId())`, `MemoryRepository.findByIdAndUserId`(이미 존재).
- Produces: `LongContextExtractor.extract(type, maskedText, UserAiContext ctx)`, `SimilarMemoryFinder.findSimilar(long userId, structured, type, UserAiContext ctx)`.

- [ ] **Step 1: 실패 테스트** — 미설정 소유자의 저장은 FAILED(stage=context), 검색 재조회는 소유자 스코프.

```java
@Test @DisplayName("소유자 AI 미설정이면 저장이 FAILED + failed_stage=context")
void unconfiguredOwnerFailsAtContext() { /* user2 소유 capture 저장 → onCaptureCreated에서 forUser 미설정 → FAILED,'context' */ }
@Test @DisplayName("S4 유사판정 재조회도 findByIdAndUserId로 소유자 유지")
void similarFinderStaysScoped() { /* 이미 SimilarMemoryFinder 스코프 테스트 있음(StoreIsolationTest) — findById→findByIdAndUserId 회귀 가드 */ }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.store.*' --tests 'com.recall.capture.CaptureFailureExposureTest'`. Expected: FAIL.

- [ ] **Step 3: 구현**
  - `StorePipeline.onCaptureCreated`: capture 로드 후 `UserAiContext ctx = contextFactory.forUser(capture.getUserId());` (미설정이면 `AiNotConfiguredException` → catch 에서 `stage="context"`, `markFailed`). stage 순서: `context → classify → extract → judge → review`.
  - `extract`/`judge` 는 `ctx.requireChat()`/`ctx.requireEmbedding()` 로 호출. `LongContextExtractor.extract(...)` 시그니처에 ctx 추가.
  - `SimilarMemoryFinder`: 내부 `memoryRepository.findById(id)` → `findByIdAndUserId(id, userId)`(소유자 끝까지 유지), 임베딩은 `ctx.requireEmbedding()`.

- [ ] **Step 4: 통과 확인** — Run 동일 + 전체. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): 저장 경로 capture 소유자 컨텍스트 + findByIdAndUserId + context 단계"`

---

## Task 8: 승인 경로 — 소유권 검증 후 forUser 임베딩

**Files:**
- Modify: `review/ReviewService.java`
- Test: `review/ReviewApproveContextTest.java` (Create)

**Interfaces:**
- Consumes: 기존 `findByIdAndUserId`(승인 소유권), `AiContextFactory.forUser(currentUser)`.
- Produces: `approve` 가 현재 사용자로 review 소유권 검증(이미 `findByIdAndUserId`) 후 `forUser(currentUserId)` 로 인덱싱 임베딩.

- [ ] **Step 1: 실패 테스트**

```java
@Test @DisplayName("승인 인덱싱은 현재 사용자 임베딩 컨텍스트 사용(미설정이면 409)")
void approveUsesOwnerEmbedding() { /* embedding 미설정 사용자가 자기 review 승인 → 인덱싱 단계에서 AiNotConfigured */ }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.review.ReviewApproveContextTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `indexForSearch` 가 주입된 싱글톤 `embeddingClient` 대신 `contextFactory.forUser(currentUser.currentUserId()).requireEmbedding()` 사용.

- [ ] **Step 4: 통과 확인** — Run 동일 + 전체. Expected: PASS.

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): 승인 인덱싱 사용자별 임베딩 컨텍스트"`

---

## Task 9: 재색인 사용자별 — reindexUser + 이벤트 userId + 세대 펜싱 + base-url 트리거

**Files:**
- Modify: `search/ReindexService.java`, `settings/EmbeddingModelChangedEvent.java`,
  `memory/MemoryRepository.java`(findActiveByUserId), `settings/SettingsService.java`(reindexNeeded + 이벤트 발행 userId)
- Test: `search/ReindexUserScopeTest.java` (Create), 기존 `ReindexServiceTest` 갱신

**Interfaces:**
- Consumes: Task 2 `updateEmbeddingStatusIfGeneration(userId,...)`, `AiContextFactory.forUser(userId)`.
- Produces: `EmbeddingModelChangedEvent(long userId, long generation)`, `ReindexService.reindexUser(long userId, long generation, UserAiContext ctx)`, `MemoryRepository.findActiveByUserId(long userId)`.

- [ ] **Step 1: 실패 테스트** — A 재색인이 B 상태 안 건드림, 이전 세대가 새 세대 상태 못 덮음, base-url 변경도 트리거.

```java
@Test @DisplayName("A 재색인 실패가 B의 embedding_status를 바꾸지 않음")
void reindexFailureIsolatedByUser() { /* user A generation UPDATE는 user_id=A 조건이라 B행 미변경 */ }
@Test @DisplayName("A generation 1 잡은 A generation 2 상태를 덮어쓰지 못함")
void staleGenerationFenced() { /* updateEmbeddingStatusIfGeneration(A,'READY',1) → 현재세대2면 0행 */ }
@Test @DisplayName("base URL 변경도 재색인 트리거(provider/model 동일해도)")
void baseUrlChangeTriggersReindex() { /* reindexNeeded가 base-url 비교 포함 */ }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests 'com.recall.search.*'`. Expected: FAIL.

- [ ] **Step 3: 구현**
  - `EmbeddingModelChangedEvent(long userId, long generation)`.
  - `SettingsService`: 임베딩 설정 저장 시 `reindexNeeded` 에 **base-url 비교 추가**(provider/model/base-url; API key만 교체는 재색인 X). 이벤트에 변경 사용자 userId 발행(설정 CRUD 는 currentUser).
  - `ReindexService.onEmbeddingModelChanged`: `reindexUser(event.userId(), event.generation(), contextFactory.forUser(event.userId()))`.
  - `reindexUser(userId, gen, ctx)`: `memoryRepository.findActiveByUserId(userId)` 만 재임베딩, `ctx.requireEmbedding()` 고정 스냅샷, 종료 시 `updateEmbeddingStatusIfGeneration(userId, status, gen)`.
  - `MemoryRepository.findActiveByUserId(long userId)` 추가. 기존 전역 `findByStatusOrderByCreatedAtDesc` 는 **기본 API 아님** 표시 유지(주석) — 이 태스크에서 ReindexService 는 더 이상 그것을 쓰지 않는다.

- [ ] **Step 4: 통과 확인** — Run 전체 `./gradlew test` (SPRING_DATASOURCE_URL 새 DB). Expected: PASS (Task 2로 깨졌던 정합도 이 시점에 전부 green).

- [ ] **Step 5: 커밋** — `git commit -m "feat(backend): 재색인 사용자별 — reindexUser·이벤트 userId·세대 펜싱·base-url 트리거"`

---

## Task 10: 🔴 교차유출/차단 릴리스 게이트 테스트 총정리

**Files:**
- Create/Modify: `settings/SettingsIsolationTest.java`, `search/ReindexUserScopeTest.java`(보강), `query/QueryContextIsolationTest.java`(보강), `store/StoreContextIsolationTest.java`(보강)
- 모두 `@Tag("release-gate")`.

**Interfaces:** Consumes: 앞 태스크 전부.

- [ ] **Step 1: 게이트 테스트 작성**(스펙 §9 전부)

```
- 설정 CRUD 사용자 격리: A가 B의 설정/apiKeyConfigured 조회·변경 불가
- A 요청에 B userId 넣어도 A 설정만 사용(요청 입력 userId 무시)
- 비동기 저장 작업 실행 중 현재 요청 사용자가 바뀌어도 capture 소유자 설정 사용
- SSE 가상 스레드에서 요청 사용자가 사라져도 컨텍스트 유지(클로저 캡처)
- A 재색인 실패가 B의 embedding_status를 바꾸지 않음
- A generation 1 잡이 A generation 2 상태를 덮어쓰지 못함
- 사용자 2는 env 키가 있어도 env 키를 사용하지 않음
- chat 키 없음 / embedding 키 없음이 각각 필요한 단계에서 차단(409 / FAILED-context)
- 저장 실패 시 failed_stage가 context까지 표현
- 컨텍스트/클라이언트 로그·toString에 API 키·복호화 설정 미노출
- 설정 변경 중 실행 중인 작업이 시작 시점 스냅샷을 사용
- initializer가 모든 사용자 행을 시드하지 않음
```
각 항목을 실제 @Test 로 — 대부분 앞 태스크에서 이미 작성됐으면 `@Tag("release-gate")` 부여 확인, 누락분만 신규 작성.

- [ ] **Step 2~4: 실행/그린** — Run: `./gradlew releaseGate` (새 DB). Expected: 위 케이스 전부 실행·PASS.

- [ ] **Step 5: 커밋** — `git commit -m "test(backend): 사용자별 AI 설정 교차유출·차단 릴리스 게이트"`

---

## 마무리

- 전체 `./gradlew test` + `releaseGate` 그린, `spotlessApply` 적용.
- backend/CLAUDE.md 의 model_setting 관련 서술(전역 단일행) 갱신은 이 플랜 범위 밖(문서 후속).
- PR 은 이 브랜치(`feature/user-auth-schema`) 위에 이어지며, 커밋은 위 태스크 단위.
