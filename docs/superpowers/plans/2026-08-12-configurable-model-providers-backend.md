# 설정형 모델 provider — 백엔드 기반 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 채팅·임베딩 모델 provider 를 DB 전역 설정으로 런타임 전환하고(BYO 키 DB 저장), 임베딩 변경 시 안전하게 재색인하는 백엔드를 만든다.

**Architecture:** 부팅 시 provider 를 고정하던 `LlmConfig` 를, 현재 설정을 읽어 위임하는 **설정 기반 프록시 빈**(`EmbeddingClient`/`LlmClient` 구현) + **팩토리**로 바꾼다. 키는 AES-GCM 으로 암호화해 `model_setting`(단일 행) 에 저장. 설정 변경은 `settings` 모듈의 REST 로 받고, 임베딩 변경 시 `@Async` 재색인 잡을 돌리며 그 동안 검색은 BM25 로 격하한다.

**Tech Stack:** Java 25, Spring Boot 4.0, JPA + PostgreSQL 17 + pgvector, Flyway, JUnit 5, RestClient.

## Global Constraints

- 스키마는 **Flyway 소유**(`ddl-auto=none`). 스키마 변경은 새 `V__*.sql` 로만, 기존 마이그레이션 수정 금지.
- **단일 사용자 전제** — `user`/`user_id` 없음. `model_setting` 은 항상 **단일 행**(id=1).
- **생성자 주입만**, 주입 필드 `final`, DTO 는 `record`, Lombok 없음.
- **공유 코드에 `switch(MemoryType)` 금지** — 유형 분기는 전략 레지스트리로만.
- **LLM/비밀 시큐어코딩(필수)**: 키 평문 저장 금지(AES-GCM), `RECALL_SECRET_KEY` 없으면 DB 영속 거부(fail-closed), 키를 로그·예외·클라이언트 응답·서드파티로 내보내지 않음, base-url 은 https 만, 역할별 capability 검증. (backend/CLAUDE.md 참조)
- **capability**: 채팅 ∈ {anthropic, openai, google}, 임베딩 ∈ {openai, voyage, google}.
- **임베딩 차원 = 1024 고정**(전 provider). DB `memory_embedding.vector vector(1024)`.
- 포맷은 spotless(AOSP): `./gradlew spotlessApply`. 로깅은 slf4j. import 는 상단.
- 커밋: 스코프 없는 conventional 타입(`feat:`/`docs:`/`test:`), **AI/Claude 협업 흔적 금지**.
- 테스트: red → green → refactor. 결정론 단계는 순수함수 테스트, 🔴 시큐어코딩 회귀는 유지.

---

## File Structure

**새 모듈 `com.recall.settings`**
- `SettingsController.java` — REST 입구(GET/PUT/catalog). HTTP 변환만.
- `SettingsService.java` — 현재 설정 조회(복호화·env 폴백)·변경(검증·암호화·저장·재색인 트리거).
- `ModelSetting.java` — `model_setting` 엔티티(단일 행).
- `ModelSettingRepository.java` — JPA 리포지토리.
- `ProviderCatalog.java` — capability 매트릭스 + provider별 모델 목록(정적).
- `dto/ModelSettingsResponse.java`, `dto/ModelSettingsRequest.java`, `dto/CatalogResponse.java`.

**`com.recall.common` (또는 `settings`) — 보안 유틸**
- `SecretCipher.java` — AES-GCM 암·복호화. `RECALL_SECRET_KEY` 로 초기화, 없으면 비활성(fail-closed).

**`com.recall.llm` — 팩토리·프록시 (기존 수정)**
- `LlmClientFactory.java` / `EmbeddingClientFactory.java` (신규) — 설정 → 클라이언트(해시 캐시).
- `SettingsBackedLlmClient.java` / `SettingsBackedEmbeddingClient.java` (신규) — 소비자에 주입되는 위임 프록시.
- `LlmConfig.java` (수정) — 단일 provider 빈 → 프록시 빈 등록.

**`com.recall.search` — 재색인·격하 (기존 수정 + 신규)**
- `ReindexService.java` (신규) — `@Async` 전 memory 재임베딩 + 상태 전이.
- `HybridSearchService.java` (수정) — REINDEXING 중 벡터 채널 끄고 BM25 만.

**리소스**
- `db/migration/V4__model_setting.sql` (신규).
- `application.yml` (수정) — `recall.security.secret-key`, 기존 임베딩/LLM 기본 유지.

---

## Task 1: SecretCipher (AES-GCM 암·복호화, fail-closed)

키를 DB 에 넣기 전에 암호화하는 유틸. 마스터키 없으면 비활성 상태로 두고, 암호화 저장을 시도하면 예외(조용한 실패 금지).

**Files:**
- Create: `backend/src/main/java/com/recall/common/SecretCipher.java`
- Test: `backend/src/test/java/com/recall/common/SecretCipherTest.java`

**Interfaces:**
- Produces:
  - `SecretCipher(String base64Key)` — key 가 null/blank 면 비활성.
  - `boolean isEnabled()`
  - `String encrypt(String plaintext)` — 비활성 시 `IllegalStateException`. 반환은 base64(iv|ciphertext).
  - `String decrypt(String encoded)` — 비활성 시 `IllegalStateException`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.recall.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static String freshKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return Base64.getEncoder().encodeToString(kg.generateKey().getEncoded());
    }

    @Test
    @DisplayName("암호화→복호화 왕복이 원문을 복원한다")
    void roundtrip() throws Exception {
        SecretCipher cipher = new SecretCipher(freshKey());
        String secret = "sk-ant-super-secret";
        String enc = cipher.encrypt(secret);
        assertNotEquals(secret, enc);
        assertEquals(secret, cipher.decrypt(enc));
    }

    @Test
    @DisplayName("같은 원문도 매번 다른 암호문(랜덤 IV)")
    void randomizedIv() throws Exception {
        SecretCipher cipher = new SecretCipher(freshKey());
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x"));
    }

    @Test
    @DisplayName("마스터키 없으면 비활성 + 암호화 시도 시 예외(fail-closed)")
    void failClosed() {
        SecretCipher cipher = new SecretCipher("  ");
        assertFalse(cipher.isEnabled());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.recall.common.SecretCipherTest'`
Expected: FAIL (SecretCipher 없음, 컴파일 에러)

- [ ] **Step 3: 최소 구현**

```java
package com.recall.common;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * provider 키의 at-rest 암호화(AES-256-GCM). 마스터키(env RECALL_SECRET_KEY, base64)가 없으면 비활성 상태로 두고,
 * 암·복호화 시도 시 예외로 드러낸다(조용한 실패 금지 — 평문 저장으로 흐르지 않게).
 */
public final class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key.trim()), "AES");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        require();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("키 암호화 실패", e);
        }
    }

    public String decrypt(String encoded) {
        require();
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("키 복호화 실패", e);
        }
    }

    private void require() {
        if (key == null) {
            throw new IllegalStateException(
                    "RECALL_SECRET_KEY 미설정 — 키를 DB에 저장/복호화할 수 없다(fail-closed)");
        }
    }
}
```

- [ ] **Step 4: 빈 등록 + 테스트 통과 확인**

`LlmConfig`(또는 새 `SecurityConfig`)에 빈 추가:
```java
@Bean
SecretCipher secretCipher(@Value("${recall.security.secret-key:}") String key) {
    return new SecretCipher(key);
}
```
`application.yml` 에:
```yaml
recall:
  security:
    secret-key: ${RECALL_SECRET_KEY:}
```
Run: `./gradlew spotlessApply test --tests 'com.recall.common.SecretCipherTest'`
Expected: PASS (3)

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/recall/common/SecretCipher.java \
        backend/src/test/java/com/recall/common/SecretCipherTest.java \
        backend/src/main/java/com/recall/llm/LlmConfig.java \
        backend/src/main/resources/application.yml
git commit -m "feat: provider 키 at-rest 암호화(AES-GCM) 유틸"
```

---

## Task 2: model_setting 마이그레이션 + 엔티티/리포지토리

단일 행 설정 테이블과 JPA 매핑. 기본 행을 seed 해 항상 id=1 이 존재하게 한다.

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__model_setting.sql`
- Create: `backend/src/main/java/com/recall/settings/ModelSetting.java`
- Create: `backend/src/main/java/com/recall/settings/ModelSettingRepository.java`
- Test: `backend/src/test/java/com/recall/settings/ModelSettingRepositoryTest.java`

**Interfaces:**
- Produces:
  - 테이블 `model_setting(id, chat_provider, chat_model, chat_api_key_enc, embedding_provider, embedding_model, embedding_api_key_enc, embedding_status, updated_at)`.
  - `ModelSetting` 엔티티 + getter/setter.
  - `ModelSettingRepository extends JpaRepository<ModelSetting, Long>`.

- [ ] **Step 1: 마이그레이션 작성**

`V4__model_setting.sql`:
```sql
-- 단일 사용자 전역 모델 설정. 항상 단일 행(id=1). 키는 애플리케이션 레벨 암호문으로만 저장.
CREATE TABLE model_setting (
    id                     BIGINT PRIMARY KEY,
    chat_provider          TEXT NOT NULL DEFAULT 'anthropic',
    chat_model             TEXT NOT NULL DEFAULT 'claude-opus-4-8',
    chat_api_key_enc       TEXT,
    embedding_provider     TEXT NOT NULL DEFAULT 'voyage',
    embedding_model        TEXT,
    embedding_api_key_enc  TEXT,
    embedding_status       TEXT NOT NULL DEFAULT 'READY',   -- READY | REINDEXING | FAILED
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 기본 행 seed (env 키를 쓰는 초기 상태; 암호문 컬럼은 비움).
INSERT INTO model_setting (id) VALUES (1);
```

- [ ] **Step 2: 엔티티 작성**

```java
package com.recall.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.UpdateTimestamp;

/** model_setting 단일 행(id=1). 키 컬럼은 암호문만 담는다. */
@Entity
@Table(name = "model_setting")
public class ModelSetting {

    @Id private Long id;

    @Column(name = "chat_provider", nullable = false)
    private String chatProvider;

    @Column(name = "chat_model", nullable = false)
    private String chatModel;

    @Column(name = "chat_api_key_enc")
    private String chatApiKeyEnc;

    @Column(name = "embedding_provider", nullable = false)
    private String embeddingProvider;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "embedding_api_key_enc")
    private String embeddingApiKeyEnc;

    @Column(name = "embedding_status", nullable = false)
    private String embeddingStatus;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ModelSetting() {}

    public Long getId() {
        return id;
    }

    public String getChatProvider() {
        return chatProvider;
    }

    public void setChatProvider(String v) {
        this.chatProvider = v;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String v) {
        this.chatModel = v;
    }

    public String getChatApiKeyEnc() {
        return chatApiKeyEnc;
    }

    public void setChatApiKeyEnc(String v) {
        this.chatApiKeyEnc = v;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String v) {
        this.embeddingProvider = v;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String v) {
        this.embeddingModel = v;
    }

    public String getEmbeddingApiKeyEnc() {
        return embeddingApiKeyEnc;
    }

    public void setEmbeddingApiKeyEnc(String v) {
        this.embeddingApiKeyEnc = v;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String v) {
        this.embeddingStatus = v;
    }
}
```

- [ ] **Step 3: 리포지토리 작성**

```java
package com.recall.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelSettingRepository extends JpaRepository<ModelSetting, Long> {}
```

- [ ] **Step 4: 리포지토리 테스트(부팅 시 seed 행 존재)**

기존 통합 테스트 패턴(@SpringBootTest 또는 @DataJpaTest + Testcontainers/실DB) 확인 후 맞춘다. 최소:
```java
package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ModelSettingRepositoryTest {

    @Autowired ModelSettingRepository repository;

    @Test
    void seedRowExists() {
        ModelSetting s = repository.findById(1L).orElseThrow();
        assertEquals("anthropic", s.getChatProvider());
        assertEquals("READY", s.getEmbeddingStatus());
    }
}
```
> 참고: 이 저장소의 통합 테스트가 DB 를 어떻게 띄우는지(기존 테스트 유무) 먼저 확인. 없으면 이 테스트는 로컬 DB(또는 Testcontainers) 필요 — `test` 스킬 따름.

- [ ] **Step 5: 실행 + 커밋**

Run: `./gradlew spotlessApply flywayMigrate test --tests 'com.recall.settings.ModelSettingRepositoryTest'` (DB 필요)
Expected: 마이그레이션 V4 적용, 테스트 PASS
```bash
git add backend/src/main/resources/db/migration/V4__model_setting.sql \
        backend/src/main/java/com/recall/settings/ModelSetting.java \
        backend/src/main/java/com/recall/settings/ModelSettingRepository.java \
        backend/src/test/java/com/recall/settings/ModelSettingRepositoryTest.java
git commit -m "feat: model_setting 단일행 테이블·엔티티·리포지토리"
```

---

## Task 3: ProviderCatalog + capability 검증

역할별 허용 provider·모델 목록(정적)과 검증 로직. 순수 로직이라 단위테스트로 고정.

**Files:**
- Create: `backend/src/main/java/com/recall/settings/ProviderCatalog.java`
- Test: `backend/src/test/java/com/recall/settings/ProviderCatalogTest.java`

**Interfaces:**
- Produces:
  - `enum Role { CHAT, EMBEDDING }`
  - `static boolean supports(Role role, String provider)`
  - `static void requireSupported(Role role, String provider)` — 미지원 시 `IllegalArgumentException`.
  - `static Map<String, List<String>> chatModels()` / `embeddingModels()` — provider→모델 목록.

- [ ] **Step 1: 실패 테스트**

```java
package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.recall.settings.ProviderCatalog.Role;
import org.junit.jupiter.api.Test;

class ProviderCatalogTest {

    @Test
    void chatAllowsAnthropicNotVoyage() {
        assertTrue(ProviderCatalog.supports(Role.CHAT, "anthropic"));
        assertFalse(ProviderCatalog.supports(Role.CHAT, "voyage"));
    }

    @Test
    void embeddingAllowsVoyageNotAnthropic() {
        assertTrue(ProviderCatalog.supports(Role.EMBEDDING, "voyage"));
        assertFalse(ProviderCatalog.supports(Role.EMBEDDING, "anthropic"));
    }

    @Test
    void requireSupportedThrowsOnInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderCatalog.requireSupported(Role.EMBEDDING, "anthropic"));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.recall.settings.ProviderCatalogTest'`
Expected: FAIL (컴파일 에러)

- [ ] **Step 3: 구현**

```java
package com.recall.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 역할별 허용 provider·모델(정적 카탈로그) + 검증. capability 비대칭(설계 §2.1). */
public final class ProviderCatalog {

    public enum Role {
        CHAT,
        EMBEDDING
    }

    private static final Set<String> CHAT_PROVIDERS = Set.of("anthropic", "openai", "google");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("openai", "voyage", "google");

    private static final Map<String, List<String>> CHAT_MODELS =
            Map.of(
                    "anthropic", List.of("claude-opus-4-8", "claude-haiku-4-5-20251001"),
                    "openai", List.of("gpt-4.1", "gpt-4.1-mini"),
                    "google", List.of("gemini-2.5-pro", "gemini-2.5-flash"));

    private static final Map<String, List<String>> EMBEDDING_MODELS =
            Map.of(
                    "openai", List.of("text-embedding-3-small", "text-embedding-3-large"),
                    "voyage", List.of("voyage-4-lite", "voyage-4", "voyage-3"),
                    "google", List.of("gemini-embedding-001"));

    private ProviderCatalog() {}

    public static boolean supports(Role role, String provider) {
        return (role == Role.CHAT ? CHAT_PROVIDERS : EMBEDDING_PROVIDERS).contains(provider);
    }

    public static void requireSupported(Role role, String provider) {
        if (!supports(role, provider)) {
            throw new IllegalArgumentException(
                    role + " 역할이 지원하지 않는 provider: " + provider);
        }
    }

    public static Map<String, List<String>> chatModels() {
        return CHAT_MODELS;
    }

    public static Map<String, List<String>> embeddingModels() {
        return EMBEDDING_MODELS;
    }
}
```
> 모델 목록은 시점에 따라 바뀔 수 있음 — 커밋 메시지에 "목록은 현재 기준, 갱신 가능" 명시.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew spotlessApply test --tests 'com.recall.settings.ProviderCatalogTest'`
Expected: PASS (3)

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/recall/settings/ProviderCatalog.java \
        backend/src/test/java/com/recall/settings/ProviderCatalogTest.java
git commit -m "feat: provider capability 카탈로그·검증"
```

---

## Task 4: 팩토리 + 설정 기반 프록시 클라이언트

기존 `EmbeddingProperties`/`LlmProperties` 로 클라이언트를 만드는 로직을 **팩토리**로 뽑고, 소비자에 주입되는 **위임 프록시**를 만든다. 프록시는 매 호출 현재 설정으로 클라이언트를 얻어 위임하므로, 소비자(StorePipeline·ReviewService·HybridSearchService·SimilarMemoryFinder) 코드는 그대로 둔다.

**Files:**
- Create: `backend/src/main/java/com/recall/llm/EmbeddingClientFactory.java`
- Create: `backend/src/main/java/com/recall/llm/LlmClientFactory.java`
- Create: `backend/src/main/java/com/recall/llm/SettingsBackedEmbeddingClient.java`
- Create: `backend/src/main/java/com/recall/llm/SettingsBackedLlmClient.java`
- Modify: `backend/src/main/java/com/recall/llm/LlmConfig.java`
- Test: `backend/src/test/java/com/recall/llm/EmbeddingClientFactoryTest.java`

**Interfaces:**
- Consumes: `EmbeddingProperties`(provider/apiKey/model/baseUrl/dimension), `LlmProperties`, `VoyageEmbeddingClient`, `OpenAiEmbeddingClient`, `AnthropicLlmClient`/`OpenAiLlmClient`/`GoogleLlmClient`, `StubEmbeddingClient`/`StubLlmClient`, `SettingsService.currentEmbedding()`/`currentChat()` (Task 5).
- Produces:
  - `EmbeddingClientFactory.forSettings(EmbeddingProperties props): EmbeddingClient` (해시 캐시).
  - `LlmClientFactory.forSettings(LlmProperties props): LlmClient`.
  - `SettingsBackedEmbeddingClient implements EmbeddingClient` / `SettingsBackedLlmClient implements LlmClient` — 소비자 주입 대상.

> **주의(의존 방향):** 프록시는 `SettingsService`(settings 모듈)를 읽는다. Task 5 에서 `SettingsService` 를 먼저 만들거나, 본 Task 는 팩토리만 만들고 프록시의 `SettingsService` 배선은 Task 5 직후로 미룬다. 아래는 팩토리 + 프록시 골격 순서.

- [ ] **Step 1: 팩토리 실패 테스트**

```java
package com.recall.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EmbeddingClientFactoryTest {

    private EmbeddingProperties props(String provider, String key) {
        return new EmbeddingProperties(provider, key, null, null, 1024);
    }

    @Test
    void keyBlankReturnsStub() {
        EmbeddingClient c = new EmbeddingClientFactory().forSettings(props("openai", ""));
        assertTrue(c instanceof StubEmbeddingClient);
    }

    @Test
    void openaiProviderReturnsOpenAiClient() {
        EmbeddingClient c = new EmbeddingClientFactory().forSettings(props("openai", "sk-x"));
        assertTrue(c instanceof OpenAiEmbeddingClient);
    }

    @Test
    void unknownProviderThrows() {
        assertThrows(
                IllegalStateException.class,
                () -> new EmbeddingClientFactory().forSettings(props("nope", "k")));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.recall.llm.EmbeddingClientFactoryTest'`
Expected: FAIL

- [ ] **Step 3: 팩토리 구현** (LlmConfig 의 switch 로직 이전)

```java
package com.recall.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 임베딩 설정 → 클라이언트. 동일 설정(provider|model|baseUrl|key 해시)은 캐시 재사용. */
public class EmbeddingClientFactory {

    private final Map<String, EmbeddingClient> cache = new ConcurrentHashMap<>();

    public EmbeddingClient forSettings(EmbeddingProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            return new StubEmbeddingClient();
        }
        String cacheKey =
                props.provider() + "|" + props.model() + "|" + props.baseUrl() + "|"
                        + Integer.toHexString(props.apiKey().hashCode());
        return cache.computeIfAbsent(cacheKey, k -> build(props));
    }

    private EmbeddingClient build(EmbeddingProperties props) {
        return switch (props.provider().toLowerCase()) {
            case "voyage" -> new VoyageEmbeddingClient(props);
            case "openai" -> new OpenAiEmbeddingClient(props);
            default ->
                    throw new IllegalStateException(
                            "알 수 없는 embedding provider: " + props.provider());
        };
    }
}
```
> `LlmClientFactory` 는 동일 패턴으로 `LlmProperties` → anthropic/openai/google(+stub). 기존 `LlmConfig.llmClient` switch 를 그대로 옮긴다.

- [ ] **Step 4: 프록시 구현** (SettingsService 는 Task 5 산출물)

```java
package com.recall.llm;

import com.recall.settings.SettingsService;

/** 소비자에 주입되는 임베딩 프록시. 매 호출 현재 설정으로 팩토리에서 클라이언트를 얻어 위임한다. */
public class SettingsBackedEmbeddingClient implements EmbeddingClient {

    private final SettingsService settings;
    private final EmbeddingClientFactory factory;

    public SettingsBackedEmbeddingClient(SettingsService settings, EmbeddingClientFactory factory) {
        this.settings = settings;
        this.factory = factory;
    }

    private EmbeddingClient current() {
        return factory.forSettings(settings.currentEmbedding());
    }

    @Override
    public int dimension() {
        return current().dimension();
    }

    @Override
    public float[] embedDocument(String text) {
        return current().embedDocument(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return current().embedQuery(text);
    }
}
```
> `SettingsBackedLlmClient` 도 동일하게 `LlmClient.complete` 위임.

- [ ] **Step 5: LlmConfig 교체 + 통과 + 커밋**

`LlmConfig` 의 `embeddingClient`/`llmClient` 빈을 프록시 등록으로 바꾼다:
```java
@Bean
EmbeddingClientFactory embeddingClientFactory() {
    return new EmbeddingClientFactory();
}

@Bean
@ConditionalOnMissingBean(EmbeddingClient.class)
EmbeddingClient embeddingClient(SettingsService settings, EmbeddingClientFactory factory) {
    return new SettingsBackedEmbeddingClient(settings, factory);
}
```
(LLM 도 동일. 기존 provider switch 코드는 팩토리로 이동했으니 제거.)

Run: `./gradlew spotlessApply test --tests 'com.recall.llm.*'`
Expected: PASS
```bash
git add backend/src/main/java/com/recall/llm/*.java \
        backend/src/test/java/com/recall/llm/EmbeddingClientFactoryTest.java
git commit -m "feat: 설정 기반 임베딩/LLM 팩토리·위임 프록시"
```

---

## Task 5: SettingsService (조회·변경·암호화·env 폴백)

DB 설정을 읽어 `EmbeddingProperties`/`LlmProperties` 로 변환(키 복호화, 없으면 env 폴백)하고, 변경을 검증·암호화·저장한다. 재색인 트리거는 Task 7 에서 배선.

**Files:**
- Create: `backend/src/main/java/com/recall/settings/SettingsService.java`
- Modify: `backend/src/main/java/com/recall/llm/EmbeddingProperties.java`, `LlmProperties.java` (env 기본값 주입원으로 유지)
- Test: `backend/src/test/java/com/recall/settings/SettingsServiceTest.java`

**Interfaces:**
- Consumes: `ModelSettingRepository`, `SecretCipher`, `EmbeddingProperties`(env 기본), `LlmProperties`(env 기본), `ProviderCatalog`.
- Produces:
  - `EmbeddingProperties currentEmbedding()` — DB provider/model + 복호화 키(없으면 env 키), dimension=1024.
  - `LlmProperties currentChat()`
  - `String embeddingStatus()` / `void setEmbeddingStatus(String)`
  - `UpdateResult update(SettingsUpdate update)` — 검증·암호화·저장. 반환에 `embeddingChanged` 포함.
  - `record SettingsUpdate(String chatProvider, String chatModel, String chatApiKey, String embeddingProvider, String embeddingModel, String embeddingApiKey)` (apiKey null=유지).
  - `record UpdateResult(boolean embeddingChanged)`

- [ ] **Step 1: 실패 테스트** (검증·env 폴백·암호화 저장 핵심)

```java
package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.SettingsService.SettingsUpdate;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;

class SettingsServiceTest {

    private static SecretCipher realCipher() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return new SecretCipher(Base64.getEncoder().encodeToString(kg.generateKey().getEncoded()));
    }

    private ModelSetting seedRow() {
        ModelSetting s = mock(ModelSetting.class);
        when(s.getChatProvider()).thenReturn("anthropic");
        when(s.getChatModel()).thenReturn("claude-opus-4-8");
        when(s.getEmbeddingProvider()).thenReturn("voyage");
        when(s.getEmbeddingStatus()).thenReturn("READY");
        return s;
    }

    @Test
    void updateRejectsUnsupportedEmbeddingProvider() throws Exception {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(seedRow()));
        SettingsService svc =
                new SettingsService(
                        repo,
                        realCipher(),
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        svc.update(
                                new SettingsUpdate(
                                        null, null, null, "anthropic", "x", "k"))); // 임베딩=anthropic 불가
    }

    @Test
    void failClosedWhenCipherDisabledAndKeyGiven() {
        ModelSettingRepository repo = mock(ModelSettingRepository.class);
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(seedRow()));
        SettingsService svc =
                new SettingsService(
                        repo,
                        new SecretCipher(""), // 비활성
                        new EmbeddingProperties("voyage", "", null, null, 1024),
                        new LlmProperties("anthropic", "", null, null, 4096));
        assertThrows(
                IllegalStateException.class,
                () -> svc.update(new SettingsUpdate(null, null, "sk-x", null, null, null)));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.recall.settings.SettingsServiceTest'`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package com.recall.settings;

import com.recall.common.SecretCipher;
import com.recall.llm.EmbeddingProperties;
import com.recall.llm.LlmProperties;
import com.recall.settings.ProviderCatalog.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전역 모델 설정의 조회·변경. 키는 복호화해 클라이언트에 넘기고, 저장 시 암호화한다(없으면 env 폴백). */
@Service
public class SettingsService {

    private final ModelSettingRepository repository;
    private final SecretCipher cipher;
    private final EmbeddingProperties envEmbedding;
    private final LlmProperties envChat;

    public SettingsService(
            ModelSettingRepository repository,
            SecretCipher cipher,
            EmbeddingProperties envEmbedding,
            LlmProperties envChat) {
        this.repository = repository;
        this.cipher = cipher;
        this.envEmbedding = envEmbedding;
        this.envChat = envChat;
    }

    private ModelSetting row() {
        return repository.findById(1L).orElseThrow(() -> new IllegalStateException("model_setting 미초기화"));
    }

    @Transactional(readOnly = true)
    public EmbeddingProperties currentEmbedding() {
        ModelSetting s = row();
        String key = decryptOr(s.getEmbeddingApiKeyEnc(), envEmbedding.apiKey());
        return new EmbeddingProperties(
                s.getEmbeddingProvider(), key, s.getEmbeddingModel(), null, 1024);
    }

    @Transactional(readOnly = true)
    public LlmProperties currentChat() {
        ModelSetting s = row();
        String key = decryptOr(s.getChatApiKeyEnc(), envChat.apiKey());
        return new LlmProperties(s.getChatProvider(), key, s.getChatModel(), null, envChat.maxTokens());
    }

    @Transactional(readOnly = true)
    public String embeddingStatus() {
        return row().getEmbeddingStatus();
    }

    @Transactional
    public void setEmbeddingStatus(String status) {
        row().setEmbeddingStatus(status);
    }

    @Transactional
    public UpdateResult update(SettingsUpdate u) {
        ModelSetting s = row();
        boolean embeddingChanged = false;

        if (u.chatProvider() != null) {
            ProviderCatalog.requireSupported(Role.CHAT, u.chatProvider());
            s.setChatProvider(u.chatProvider());
        }
        if (u.chatModel() != null) s.setChatModel(u.chatModel());
        if (notBlank(u.chatApiKey())) s.setChatApiKeyEnc(encrypt(u.chatApiKey()));

        if (u.embeddingProvider() != null) {
            ProviderCatalog.requireSupported(Role.EMBEDDING, u.embeddingProvider());
            embeddingChanged |= !u.embeddingProvider().equals(s.getEmbeddingProvider());
            s.setEmbeddingProvider(u.embeddingProvider());
        }
        if (u.embeddingModel() != null) {
            embeddingChanged |= !u.embeddingModel().equals(s.getEmbeddingModel());
            s.setEmbeddingModel(u.embeddingModel());
        }
        if (notBlank(u.embeddingApiKey())) s.setEmbeddingApiKeyEnc(encrypt(u.embeddingApiKey()));

        return new UpdateResult(embeddingChanged);
    }

    private String encrypt(String plaintext) {
        if (!cipher.isEnabled()) {
            throw new IllegalStateException(
                    "RECALL_SECRET_KEY 미설정 — UI 입력 키를 저장할 수 없다(fail-closed)");
        }
        return cipher.encrypt(plaintext);
    }

    private String decryptOr(String enc, String envFallback) {
        if (enc == null || enc.isBlank()) return envFallback;
        return cipher.decrypt(enc);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    public record SettingsUpdate(
            String chatProvider,
            String chatModel,
            String chatApiKey,
            String embeddingProvider,
            String embeddingModel,
            String embeddingApiKey) {}

    public record UpdateResult(boolean embeddingChanged) {}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew spotlessApply test --tests 'com.recall.settings.SettingsServiceTest'`
Expected: PASS (2). (Mockito 의존 없으면 build.gradle 에 `spring-boot-starter-test` 이미 포함 — 확인)

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/recall/settings/SettingsService.java \
        backend/src/test/java/com/recall/settings/SettingsServiceTest.java
git commit -m "feat: SettingsService — 설정 조회·변경·키 암호화·env 폴백"
```

---

## Task 6: test-before-save (임베딩 프로브)

새 임베딩 설정으로 프로브 임베딩 1회를 시도해 저장 전에 유효성을 확인한다. 실패면 `update` 자체가 던져 컨트롤러가 400 으로 변환.

**Files:**
- Modify: `backend/src/main/java/com/recall/settings/SettingsService.java`
- Test: `backend/src/test/java/com/recall/settings/SettingsServiceProbeTest.java`

**Interfaces:**
- Consumes: `EmbeddingClientFactory.forSettings(...)`.
- Produces: `SettingsService.update(...)` 가 임베딩 변경 시 프로브 성공을 요구(실패 시 `EmbeddingProbeException`).

- [ ] **Step 1: 실패 테스트** — 프로브 실패 시 저장 안 됨

```java
package com.recall.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.recall.llm.EmbeddingClient;
import com.recall.llm.EmbeddingClientFactory;
import com.recall.settings.SettingsService.SettingsUpdate;
import org.junit.jupiter.api.Test;

class SettingsServiceProbeTest {

    @Test
    void probeFailureRejectsSaveAndNoStatusChange() {
        // factory 가 예외 던지는 임베딩 클라이언트를 반환하도록 구성
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        EmbeddingClient bad = mock(EmbeddingClient.class);
        when(bad.embedDocument(anyString())).thenThrow(new RuntimeException("401 unauthorized"));
        when(factory.forSettings(any())).thenReturn(bad);
        // ... SettingsService 를 factory 주입형으로 구성해 update(embeddingProvider=openai) 시
        // EmbeddingProbeException 이 나고 repository.save 가 호출되지 않음을 검증
    }
}
```
> 구현 시 `SettingsService` 생성자에 `EmbeddingClientFactory` 를 추가하고, 임베딩이 바뀔 때 `factory.forSettings(candidateProps).embedDocument("probe")` 를 try/catch 로 감싸 실패 시 `EmbeddingProbeException` 을 던진다. 위 테스트를 그 시그니처에 맞춰 완성.

- [ ] **Step 2: 실패 확인** → **Step 3: 구현** (update 내 임베딩 변경 분기에 프로브 추가)

```java
if (embeddingChanged) {
    EmbeddingProperties candidate = candidateEmbeddingProps(s);
    try {
        embeddingFactory.forSettings(candidate).embedDocument("probe");
    } catch (Exception e) {
        throw new EmbeddingProbeException("임베딩 설정 검증 실패(키/모델 확인): " + e.getMessage());
    }
}
```
`EmbeddingProbeException extends RuntimeException` 신규.

- [ ] **Step 4: 통과** — `./gradlew spotlessApply test --tests 'com.recall.settings.SettingsServiceProbeTest'`
- [ ] **Step 5: 커밋** — `git commit -m "feat: 임베딩 설정 저장 전 프로브 검증(test-before-save)"`

---

## Task 7: ReindexService (@Async) + 상태 전이

임베딩 변경 시 활성 memory 를 새 모델로 재임베딩하고 상태를 전이한다. 저장 경로 성격(@Async).

**Files:**
- Create: `backend/src/main/java/com/recall/search/ReindexService.java`
- Modify: `backend/src/main/java/com/recall/settings/SettingsService.java` (update → embeddingChanged 시 reindex 트리거)
- Test: `backend/src/test/java/com/recall/search/ReindexServiceTest.java`

**Interfaces:**
- Consumes: `MemoryRepository`(활성 memory), `searchReps`(`StrategyRegistry<SearchRepresentation>`), `EmbeddingClient`(프록시), `MemorySearchStore.saveEmbedding(...)`, `SettingsService.setEmbeddingStatus(...)`.
- Produces: `@Async void reindexAll()` — 각 memory 재임베딩 → 완료 시 status READY, 실패 시 FAILED.

- [ ] **Step 1: 실패 테스트** — 재임베딩이 각 memory 에 대해 saveEmbedding 을 호출하고 끝에 READY

```java
package com.recall.search;
// mock: memoryRepository.findAllActive() → [m1, m2], searchReps.embeddingTexts → {"document":"t"},
// embeddingClient.embedDocument → float[1024]. reindexAll() 후:
//  - searchStore.saveEmbedding 이 2회 호출
//  - settings.setEmbeddingStatus("READY") 호출
// 실패 케이스: embedDocument 예외 → setEmbeddingStatus("FAILED")
```
> 기존 `ReviewService` 의 재임베딩 로직(§ReviewService line ~92-96: `searchReps.get(type).embeddingTexts` + `searchStore.saveEmbedding(memoryId, kind, embeddingClient.embedDocument(text))`)을 재사용 참고해 시그니처를 맞춘다.

- [ ] **Step 2~4: 구현·통과**

```java
package com.recall.search;

import ...;

@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final MemoryRepository memoryRepository;
    private final MemorySearchStore searchStore;
    private final EmbeddingClient embeddingClient;
    private final StrategyRegistry<SearchRepresentation> searchReps;
    private final SettingsService settings;

    // 생성자 주입 ...

    @Async
    public void reindexAll() {
        try {
            for (Memory m : memoryRepository.findAllActive()) {
                Map<String, Object> structured = parse(m.getProposedOrStructured());
                Map<String, String> texts = searchReps.get(m.getType()).embeddingTexts(structured);
                texts.forEach(
                        (kind, text) ->
                                searchStore.saveEmbedding(
                                        m.getId(), kind, embeddingClient.embedDocument(text)));
            }
            settings.setEmbeddingStatus("READY");
            log.info("재색인 완료");
        } catch (Exception e) {
            settings.setEmbeddingStatus("FAILED");
            log.error("재색인 실패 — 상태 FAILED", e);
        }
    }
}
```
> `MemoryRepository.findAllActive()`·구조화 JSON 접근자는 기존 코드에 맞춰 정확히 연결(없으면 추가). `@EnableAsync` 는 `RecallApplication` 에 이미 있음.

`SettingsService.update` 끝에서:
```java
if (result.embeddingChanged()) {
    setEmbeddingStatus("REINDEXING");
    reindexService.reindexAll(); // @Async
}
```
(순환 주의: settings → search(ReindexService). ReindexService 는 settings.setEmbeddingStatus 만 호출 — 상태 setter 는 별도 인터페이스로 좁혀 순환을 끊거나, `@Lazy` 주입.)

- [ ] **Step 5: 커밋** — `git commit -m "feat: 임베딩 변경 시 @Async 재색인 + 상태 전이"`

---

## Task 8: 재색인 중 검색 BM25 격하

`embedding_status = REINDEXING` 이면 벡터 채널을 끄고 BM25 만 사용. 상태는 응답에 노출(격하 표시).

**Files:**
- Modify: `backend/src/main/java/com/recall/search/HybridSearchService.java`
- Test: `backend/src/test/java/com/recall/search/HybridSearchDegradeTest.java`

**Interfaces:**
- Consumes: `SettingsService.embeddingStatus()`.
- Produces: `HybridSearchService.search(...)` 가 REINDEXING 중엔 `store.searchByVector` 를 호출하지 않고 BM25 결과만 융합.

- [ ] **Step 1: 실패 테스트** — REINDEXING 이면 searchByVector 미호출

```java
// mock settings.embeddingStatus() → "REINDEXING"
// search(...) 호출 후: verify(store, never()).searchByVector(any(), any(), anyInt());
//                      verify(store).searchByKeyword(...);
```

- [ ] **Step 2~4: 구현·통과**

`search()` 를 수정:
```java
public List<Memory> search(String question, MemoryType type) {
    boolean reindexing = "REINDEXING".equals(settings.embeddingStatus());
    List<Long> vectorIds =
            reindexing
                    ? List.of()
                    : ids(store.searchByVector(embeddingClient.embedQuery(question), type, CHANNEL_K));
    List<Long> bm25Ids = ids(store.searchByKeyword(question, type, CHANNEL_K));
    Map<String, List<Long>> ranked = Map.of(CH_VECTOR, vectorIds, CH_BM25, bm25Ids);
    ...
}
```
> `SettingsService` 를 생성자 주입으로 추가. RRF 는 빈 채널(vectorIds=[])을 자연히 무시(가중치 融合에서 0 기여) — `RrfFusion` 이 빈 리스트를 안전 처리하는지 테스트로 확인, 아니면 보정.

- [ ] **Step 5: 커밋** — `git commit -m "feat: 재색인 중 벡터 채널 격하(BM25만)"`

---

## Task 9: SettingsController (GET/PUT/catalog)

REST 입구 + DTO + 예외 → 400 변환(기존 `@RestControllerAdvice` 활용).

**Files:**
- Create: `backend/src/main/java/com/recall/settings/SettingsController.java`
- Create: `backend/src/main/java/com/recall/settings/dto/ModelSettingsResponse.java`, `dto/ModelSettingsRequest.java`, `dto/CatalogResponse.java`
- Modify: `backend/src/main/java/com/recall/common/ApiExceptionHandler.java` (IllegalArgumentException/EmbeddingProbeException → 400)
- Test: `backend/src/test/java/com/recall/settings/SettingsControllerTest.java` (@WebMvcTest)

**Interfaces:**
- Consumes: `SettingsService`.
- Produces:
  - `GET /api/settings/models` → `ModelSettingsResponse`(키는 `apiKeyConfigured` 만).
  - `PUT /api/settings/models` (`ModelSettingsRequest`) → 200/400.
  - `GET /api/settings/models/catalog` → `CatalogResponse`(ProviderCatalog).

- [ ] **Step 1: 실패 테스트** (@WebMvcTest) — GET 응답에 키 평문 없음, PUT 잘못된 조합 400

```java
// @WebMvcTest(SettingsController.class), MockBean SettingsService
// GET → status 200, json 에 apiKeyConfigured 존재, "sk-" 문자열 미포함
// PUT embedding.provider=anthropic → SettingsService.update 가 IllegalArgumentException →
//   ApiExceptionHandler 가 400
```

- [ ] **Step 2~4: 구현·통과**

DTO(응답은 키 평문 절대 미포함):
```java
public record ModelSettingsResponse(Slot chat, EmbeddingSlot embedding) {
    public record Slot(String provider, String model, boolean apiKeyConfigured) {}
    public record EmbeddingSlot(
            String provider, String model, boolean apiKeyConfigured, String status) {}
}
```
컨트롤러 GET 은 `SettingsService` 에서 provider/model/status + `apiKeyConfigured`(암호문/env 존재 여부)만 뽑아 반환. `ApiExceptionHandler` 에 매핑 추가:
```java
@ExceptionHandler({IllegalArgumentException.class, EmbeddingProbeException.class})
ResponseEntity<?> badRequest(RuntimeException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
}
```

- [ ] **Step 5: 커밋** — `git commit -m "feat: 설정 REST API(GET/PUT/catalog)"`

---

## Task 10: 통합 스모크 (실연동 확인)

부팅 → GET 설정 → PUT(임베딩 provider 변경) → status REINDEXING→READY → 검색 정상.

**Files:**
- Test: `backend/src/test/java/com/recall/settings/SettingsFlowSmokeTest.java` (@SpringBootTest, 실 DB)

- [ ] **Step 1~4:** 부팅 후 `GET /api/settings/models` 기본값 확인 → `PUT` 로 embedding provider 변경(stub 키로 프로브 우회 불가하므로, 이 스모크는 프로브를 통과하는 더미/실키 또는 프로브 비활성 프로파일로) → `embedding_status` 폴링이 READY 로 수렴 → `POST /api/query` 가 5xx 없이 응답.
> 실 provider 호출이 필요한 부분은 `@Disabled`/수동 스모크로 분리하고, CI 는 stub 경로(키 없음)로 부팅·라우팅만 검증.

- [ ] **Step 5: 커밋** — `git commit -m "test: 설정 변경·재색인·검색 통합 스모크"`

---

## Self-Review (작성자 체크 결과)

- **Spec coverage**: §3 팩토리→T4, §4 테이블→T2, §5 재색인→T7·T8, §6 API→T9, §7 시큐어코딩→T1·T5·T9, §2.1 capability→T3. Google 임베딩(§11 phase2)·프론트(§8 phase4)는 **본 계획 범위 밖**(별도 계획) — 명시됨.
- **Placeholder scan**: T6·T7·T8·T10 의 테스트 본문은 mock 시그니처를 구현 시 확정하도록 주석으로 남김(기존 코드 시그니처 의존). 실행 시 해당 파일(ReviewService·MemoryRepository·RrfFusion) 확인 필요 — 계획에 파일·라인 근거 명시.
- **Type consistency**: `EmbeddingProperties(provider, apiKey, model, baseUrl, dimension)` 시그니처를 T4·T5 에서 일관 사용. `currentEmbedding/currentChat/embeddingStatus/setEmbeddingStatus/update` 이름 T4~T9 일관.

## 범위 밖 (후속 계획)

- **Plan B**: Google 임베딩 클라이언트(`GoogleEmbeddingClient`, output_dimensionality=1024) — 임베딩 provider 3종 완성.
- **Plan C**: 프론트 `SettingsPage`(catalog 드롭다운·키 입력·재색인 배너/폴링).
- 차원 1536 상향(Eval 측정 후, 별도 마이그레이션).
