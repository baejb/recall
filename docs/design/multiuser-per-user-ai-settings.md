# 멀티유저 — 사용자별 AI 설정(키·모델) 설계

> 작성 2026-08-18 · 브랜치 `feature/user-auth-schema` · 상태: 설계 확정, 구현 대기
> 선행: 이 브랜치의 멀티유저 격리(app_user + user_id 스코핑, CurrentUserProvider seam).

## 1. 목표

각 사용자가 자기 LLM/임베딩 **provider·키를 직접 소유(BYO)**하게 한다. 지금은
`model_setting`이 전역 단일 행(id=1)이라 BYO 키가 전 사용자 공유 — 멀티유저 격리(교차유출
금지)와 정면으로 어긋난다. 이 설계는 설정을 사용자별로 분리하고, "이 LLM/임베딩 호출이
누구 키를 쓰는가"를 파이프라인 전 구간에서 명시적으로 강제한다.

모든 규칙은 단 하나의 불변식을 위한다: **한 사용자의 키·설정·기억이 다른 사용자에게 새지
않는다(교차유출 금지).**

## 2. 제품 동작 (확정)

- 사용자마다 자기 chat/embedding provider·키·모델을 가진다.
- **키 미설정 사용자는 LLM 필요 동작(추출 S2·긴맥락 S3·판정 S4·답변 A·임베딩)이 차단**된다.
  env 공유 폴백 없음(남의/소유자 키로 대신 돌지 않는다).
- **부트스트랩 사용자(id=1)만** 기존 env 시드를 유지(현재 단일 인스턴스 무중단).
- 임베딩 provider/모델을 바꾸면 **본인 기억만** 재색인한다(남의 기억·비용 무관).
- 설정 조회/변경은 본인 것만. 남의 `apiKeyConfigured`/마스킹 상태도 못 본다(키 교차유출 금지).

## 3. 핵심 결정 — 명시적 도구 전달(방식 B)

"이 호출이 누구 설정을 쓰나"를 **ThreadLocal 같은 암묵적 주변 상태로 두지 않는다.** 교차유출
금지가 최우선 불변원칙이므로, 컴파일러가 강제하는 **명시적 전달**을 택한다(코드 수정량이 아니라
안전성·스레드 안전·테스트/디버깅 용이성 기준의 결정).

### 3.1 UserAiContext (단일 객체로 인자 관통 완화)

```
UserAiContext (불변 immutable) {
    long userId;              // 소유자/스코프 (setter 없음)
    LlmClient llm;            // 그 사용자 chat 설정으로 바인딩
    EmbeddingClient embedding;// 그 사용자 embedding 설정으로 바인딩
    boolean chatReady;        // chat 키/모델 설정 완료 여부
    boolean embeddingReady;   // embedding 키/모델 설정 완료 여부
}
```

- **AiContextFactory.forUser(long userId): UserAiContext** — 그 사용자의 `model_setting`을
  읽어 바인딩된 llm/embedding 을 만든다. `forUser`는 **소유권을 추론하지 않는다** — 단지 "그 ID의
  설정을 읽어 컨텍스트를 만든다". userId 의 신뢰는 호출부 책임(§3.3).
- LLM/임베딩을 호출하는 계층은 **반드시 UserAiContext(또는 그 안의 llm/embedding)를 인자로
  받는다.** 컨텍스트 없이 호출 불가.
- **비동기/스레드풀/SSE 작업은 컨텍스트를 클로저·작업 로컬에 담아** 넘긴다(스레드 경계에서
  재설정 불필요, 스레드풀 잔재로 인한 조용한 교차유출 없음).
- **UserAiContext 의 llm/embedding 은 진입점에서 한 번 바인딩한 고정 스냅샷**이다 — 매 호출마다
  설정을 다시 읽는 프록시가 아니다(현재 `SettingsBacked*Client` 는 호출마다 재조회하므로 이 구조로
  교체). 한 작업(요청·저장잡·재색인 잡) 안에서는 시작 시점 설정으로 완주하며, 실행 중 설정이 바뀌어도
  그 작업에는 섞이지 않는다(조회·저장·재색인 전 진입점 동일).
- `UserAiContext.toString()` 은 provider 설정·키·복호화 값을 **절대 노출하지 않는다**(로그 방어).

### 3.2 컴파일러가 보장하는 것과 못 하는 것 (경계 명확화)

컴파일러는 **"컨텍스트가 전달됐는가"** 만 보장한다. **"컨텍스트의 userId 와 다루는 데이터의
소유자가 같은가"** 까지는 보장하지 못한다. 그 일치는 다음으로 **런타임 검증**한다:

- 데이터 접근은 끝까지 소유자 조건 유지: 검색이 준 id 를 다시 읽을 때도 `findByIdAndUserId(id,
  ctx.userId)` 를 쓴다(예: SimilarMemoryFinder 가 지금 `findById` 로 재조회하는 부분 →
  `findByIdAndUserId` 로 강화).
- 어떤 단계든 `ctx.userId != memory/capture.userId` 이면 **즉시 도메인 예외/IllegalStateException**
  으로 중단(조용히 진행 금지).

### 3.3 신뢰 경계 — userId 는 요청 입력에서 절대 얻지 않는다

**HTTP 요청 파라미터/바디의 userId 를 소유자 결정에 쓰지 않는다(금지 패턴).** 각 진입점은
신뢰된 출처에서만 소유자를 얻는다:

| 경로 | 소유자(신뢰 출처) | 컨텍스트 해석 시점 |
|------|------------------|--------------------|
| 조회 답변(동기 SSE) | `CurrentUserProvider` | 요청 경계에서 forUser → 파이프라인에 전달 |
| 저장 파이프라인(@Async) | **DB에서 읽은** `capture.user_id` | StorePipeline.onCaptureCreated 시작 시 forUser |
| 승인 인덱싱 | 현재 사용자로 **review 소유권 먼저 검증한 뒤** 그 사용자 | ReviewService.approve |
| 재색인(배경) | **시스템이 DB에서 순회한** userId | ReindexService 사용자별 루프 |
| 설정 CRUD | 현재 사용자 | SettingsController |

싱글톤 프록시(`SettingsBackedLlmClient`/`EmbeddingClient`)를 파이프라인 깊은 곳에 주입하던
구조는, 진입점에서 바인딩된 클라이언트를 만들어 UserAiContext 로 넘기는 구조로 바꾼다.

## 4. 미설정 차단 vs 외부 장애 격하 (정책 분리) — P1

현재 `AnswerStreamer` 는 `pipeline.llmReady()==false` 면 BM25/요약으로 격하한다. 이 하나로
**"설정 미완료"** 와 **"설정은 됐으나 외부 API 일시 실패"** 를 섞으면 안 된다. 두 상황을 분리한다:

- **설정 미완료/키 없음 → 도메인 예외로 차단**(격하 아님). env·타 사용자 키로 대체하지 않는다.
  - **chat 키 없음** → LLM 단계(분류 C·리랭크 RR·답변 A)가 필요한 요청 차단. (BM25만으로 카드
    반환이 가능한 경로는 임베딩 정책을 따른다 — 아래.)
  - **embedding 키 없음** → 저장(추출 후 임베딩)·승인 인덱싱·재색인은 **하드 차단**(저장은 FAILED
    `stage=context`, 승인은 409+롤백, 재색인은 FAILED). 단 **조회의 임베딩 검색 채널만은 차단이 아니라
    격하** — 벡터 채널을 끄고 BM25 키워드 검색만으로 응답한다(chat 이 설정돼 있으면 답변은 가능하므로
    요청 자체를 막지 않는다). 즉 "차단"은 쓰기/인덱싱 경로, "격하"는 조회 벡터 채널.
- **설정 완료 후 외부 API 일시 실패 → 기존 격하 정책 유지**(RR 실패→W 결과, A 실패→검색 카드,
  벡터 실패→BM25) 또는 명시적 오류. 이건 "설정은 정상"인 상태의 런타임 실패다.

구현 계약:
- `UserAiContext.chatReady/embeddingReady` 로 **설정 완료 상태를 검증**하고, 각 호출 단계는
  **어떤 capability(chat/embedding)가 필요한지 드러낸다.** `available()` 하나로 두 상황을
  뭉뚱그리지 않는다(설정 미완료 ≠ 외부 장애).
- 저장 파이프라인 실패 단계(`failed_stage`)에 컨텍스트 바인딩 단계를 추가:
  `classify | extract | judge | review` → **`context(bind)` 추가**. 미설정으로 컨텍스트 생성이
  실패하면 `failed_stage=context` 로 드러난다(조용한 실패 금지).

## 5. 스키마 (Flyway V12)

- `model_setting` 에 `user_id BIGINT REFERENCES app_user(id)` (+ 아래 FK 삭제 정책) + `UNIQUE(user_id)`,
  이후 NOT NULL.
- 사용자별 1행. 조회는 `findByUserId`. `embedding_status`·`embedding_generation` 은 model_setting
  행에 있으므로 사용자별 행으로 옮기면 **상태·세대가 자연히 사용자별**이 된다(§6).
- 키는 기존과 동일하게 **at-rest AES-GCM 암호화** 유지(backend/CLAUDE.md 시큐어코딩).

### 5.1 기존 행 귀속 — 전제 검증 + fail-loud (무조건 자동 귀속 금지)

단일 사용자 시절엔 소유자가 저장돼 있지 않으므로 "id=1 → user_id=1"은 가정이다. 마이그레이션은
전제를 검증하고 깨지면 중단한다:

```sql
-- (a) 부트스트랩 사용자 존재 검증 — V11 선행에 의존하되 SQL에서 명시해 실패 원인을 분명히.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = 1) THEN
    RAISE EXCEPTION '부트스트랩 사용자(app_user.id=1) 없음 — V11 시드 누락, model_setting 귀속 불가';
  END IF;
  -- (b) 단일 사용자 불변: 0행 또는 id=1 단일행이어야 자동 귀속 가능.
  IF (SELECT count(*) FROM model_setting) > 1
     OR EXISTS (SELECT 1 FROM model_setting WHERE id <> 1) THEN
    RAISE EXCEPTION 'model_setting 단일행(id=1) 전제 위반 — 소유자 자동 귀속 불가, 수동 마이그레이션 필요';
  END IF;
END $$;
ALTER TABLE model_setting ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE RESTRICT;
UPDATE model_setting SET user_id = 1 WHERE id = 1;   -- 있으면만 부트스트랩 귀속(0행이면 무동작)
ALTER TABLE model_setting ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE model_setting ADD CONSTRAINT uq_model_setting_user UNIQUE (user_id);
```

### 5.2 사용자 삭제 정책 (FK ON DELETE) — 명시 결정

`model_setting.user_id` 는 **`ON DELETE RESTRICT`** 로 둔다: 설정 행은 (암호화된) 키를 담으므로
사용자를 지우면 자동으로 키 설정이 사라지는 걸 막고, **사용자 삭제는 애플리케이션의 명시적
삭제 플로우**(설정·기억까지 의도적으로 정리)를 거치게 강제한다. 운영자가 기본 동작을 추측하지
않도록 문서·마이그레이션에 못박는다. (도메인 데이터 `capture/memory/review_queue` 의 user_id 는
격리 PR에서 `ON DELETE CASCADE` — 사용자 삭제 플로우가 정한 순서로 정리.)

## 6. 사용자별 재색인 — 데이터 + 상태 모두 사용자별 — P1

전역 재색인 구조를 사용자별로 강하게 바꾼다. **전역 스윕 메서드를 기본 API로 남기지 않는다**
(나중에 잘못 재사용될 위험).

- 기본 API(사용자별):
  - `reindexUser(userId, generation, embeddingContext)`
  - `MemoryRepository.findActiveByUserId(userId)` — 기존 `findByStatusOrderByCreatedAtDesc("active")`
    (전역)를 대체하는 기본 경로.
  - `updateEmbeddingStatusIfGeneration(userId, status, generation)` — user_id + generation 조건부 UPDATE.
- 전(全) 사용자 관리자 잡이 필요하면 **내부에서 명시적으로** `for userId in userIds: reindexUser(...)`.
  전역 스윕은 **별도 이름·권한을 가진 운영 기능**으로 분리(기본 API 아님).

### 6.1 세대(generation) 상태 전이 규칙 — P2

- `model_setting(user_id, embedding_generation)` — generation 은 **해당 user_id 안에서만** 증가.
- 재색인 잡은 **시작 시점의 UserAiContext 를 고정**(중간에 설정이 바뀌어도 그 스냅샷으로 완주).
- 완료/실패 상태 갱신은 **user_id + generation 조건부 UPDATE**. 더 최신 generation 이 진행 중이면
  옛 잡이 상태를 **덮어쓰지 않는다**(펜싱 — 기존 전역 펜싱을 사용자별로).
- **무엇이 재색인을 일으키나:** provider 변경 · model 변경 · base URL 변경 · 차원(dimension)에
  영향 줄 수 있는 변경. **API key 만 교체하면 재색인하지 않는다.**
- 벡터 차원은 1024 고정 → provider/model 이 1024차원을 보장하는지 **AiContextFactory/설정 검증
  단계에서 확인**(불일치 설정은 거부).

## 7. 컴포넌트 변경 요약

- **SettingsService** — `chatFor(userId)`/`embeddingFor(userId)` 로 사용자별 설정 해석. CRUD
  (get/update/probe)는 현재 사용자 스코프. 미설정이면 차단 예외.
  - **env fallback 은 부트스트랩 사용자에게만.** `decryptOr(enc, envFallback)` 같은 일반 함수는
    사용자별 전환 후 위험하므로 **제거하거나 bootstrap 전용 분기**로 격리.
- **AiContextFactory**(신규) — forUser(userId) → UserAiContext(바인딩 llm/embedding + ready 플래그).
- **QueryPipeline / AnswerStreamer / QueryController** — UserAiContext 를 받아 classify·retrieve
  ·rerank·compose·embedQuery 가 그 llm/embedding 사용. 미설정은 차단(§4).
- **StorePipeline** — 잡 시작 시 forUser(capture.userId)(DB 소유자), LongContextExtractor·
  SimilarMemoryFinder 에 컨텍스트 전달. 미설정이면 FAILED(`failed_stage=context`).
- **ReviewService.approve/indexForSearch** — 현재 사용자로 review 소유권 검증 후 forUser 로 임베딩.
- **ReindexService** — 사용자별 루프 + forUser + §6 세대 펜싱.
- **ModelSettingInitializer** — **부트스트랩 사용자(1) 행만** env 시드. 다른 사용자 행을 시드하지
  않는다.

## 8. 불변 원칙 게이트

- 마스킹/시크릿: 키 at-rest 암호화·마스킹 반환·로그 금지 유지. 컨텍스트·클라이언트 로그/`toString`에
  키·복호화 설정 노출 금지.
- 교차유출 금지: 설정 조회/변경/키 사용/재색인이 전부 소유자 스코프. userId 는 신뢰 출처만(§3.3).
  🔴 릴리스 게이트로 방어(§9).
- 조용한 실패 금지: 미설정 차단은 상태/예외로 드러낸다(저장 `failed_stage=context`, 조회 명확한 에러).
- 결정론 단계 불변: provider 해석만 사용자별로 바꾸며 결정/확률 단계 경계는 유지.

## 9. 테스트 (🔴 릴리스 게이트 포함)

기본 격리:
- 설정 CRUD 사용자 격리: A가 B의 설정/`apiKeyConfigured`를 조회·변경 불가.
- 키 사용 격리: 두 사용자 서로 다른 provider/키 → forUser 가 각자 것으로 바인딩(교차 없음).
- 재색인 사용자별: A의 임베딩 변경이 B 기억을 건드리지 않음.

리뷰 보강분:
- **A 요청에 B의 userId 를 넣어도 A 설정만 사용**(요청 입력 userId 무시 — §3.3).
- **비동기 저장 작업 실행 중 현재 요청 사용자가 바뀌어도 capture 소유자 설정 사용**(스레드 격리).
- **SSE 가상 스레드에서 요청 사용자가 사라져도 컨텍스트 유지**(클로저 캡처).
- **A 재색인 실패가 B의 embedding_status 를 바꾸지 않음**.
- **A generation 1 잡이 A generation 2 의 상태를 덮어쓰지 못함**(세대 펜싱).
- **사용자 2 는 env 키가 있어도 env 키를 사용하지 않음**(부트스트랩 전용 fallback).
- **chat 키 없음 / embedding 키 없음 이 각각 필요한 단계에서 차단됨**(capability 분리).
- **저장 실패 시 `failed_stage` 가 컨텍스트 바인딩 단계(context)까지 표현됨**.
- **컨텍스트/클라이언트 로그에 API 키·복호화 설정이 남지 않음**.
- **설정 변경 중 실행 중인 작업이 어떤 설정 스냅샷을 사용하는지 검증**(잡 시작 시점 고정).
- **initializer 가 모든 사용자 행을 시드하지 않음**.

## 10. 범위 밖 / 후속

- 실제 OAuth 로그인·principal→user_id 해석(팀원). 지금은 부트스트랩(1) 기준으로 검증.
- 사용자별 설정 UI 상세(프론트)는 후속.
- 사용자 삭제 플로우 자체(설정·기억 정리 순서)는 별도 — 이 문서는 FK 정책(RESTRICT)만 확정.
- PRD·루트 CLAUDE.md의 "단일 사용자" 전제 문서 갱신(별도).
