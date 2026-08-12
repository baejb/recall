# 설계 — 사용자 설정형 모델 provider (채팅 + 임베딩)

- 날짜: 2026-08-12
- 브랜치: feature/frontend-integration
- 상태: 설계 확정(구현 계획 전)

## 1. 목적

단일 사용자가 **설정 화면(UI)에서 채팅(생성) 모델과 임베딩 모델을 provider 단위로 선택·수정하고
BYO 키를 입력**할 수 있게 한다. 지금은 부팅 시 env 로 provider 하나가 고정되지만, 이를 **런타임에
바꿀 수 있는 전역 설정**으로 만든다. env 편집·재배포 없이 UI 로 전환하는 것이 목표.

## 2. 핵심 제약 (설계를 규정하는 사실)

### 2.1 provider capability 는 비대칭

"4개 provider 가 채팅·임베딩 둘 다"가 아니다. 역할별로 가능한 provider 가 다르다.

| provider | 채팅(생성) | 임베딩 |
|----------|:---:|:---:|
| Anthropic | ✅ | ❌ (임베딩 API 없음) |
| OpenAI | ✅ | ✅ |
| Google | ✅ | ✅ (클라이언트 미구현 — 본 설계에서 추가) |
| Voyage | ❌ (채팅 안 함) | ✅ |

- **채팅 가능**: anthropic, openai, google
- **임베딩 가능**: openai, voyage, google
- UI·백엔드 모두 **불가능 조합(Anthropic 임베딩·Voyage 채팅)을 거부**한다.

### 2.2 임베딩 차원 = 1024 고정 (전 provider 공통)

- DB `memory_embedding.vector` 는 `vector(1024)` 로 고정. 모든 벡터가 정확히 1024차원이어야 한다.
- 후보 provider 전부 1024 출력 가능: voyage-3 native, voyage-4 Matryoshka, OpenAI `dimensions=1024`,
  Google `output_dimensionality=1024`.
- **1024 유지 결정**: 순수 성능상 1024 vs 1536 차이는 미미(marginal)하고, 차원은 검색 품질의 작은
  손잡이(모델·검색표현·하이브리드·리랭크가 더 큰 레버). 차원 상향은 **Eval 라벨셋에서 유의미
  개선이 측정될 때만** 새 마이그레이션 + 전면 재색인으로 진행한다(직관 금지 — PRD Eval 원칙).

### 2.3 임베딩 모델 변경 = 전면 재색인 필요

- 임베딩 provider/model 을 바꾸면 저장된 벡터가 **다른 벡터 공간**이 되어 무효화된다(차원이 같아도
  코사인 유사도 무의미). → **전 memory 를 새 모델로 재임베딩** 해야 한다.
- 채팅 모델 변경은 저장물에 의존하지 않으므로 **재색인 불필요**(즉시 반영).

## 3. 아키텍처 — "부팅 단일 빈" → "설정 기반 팩토리"

현재 `LlmConfig` 는 부팅 시 env 로 provider 하나를 골라 `@ConditionalOnMissingBean` 으로 **빈 1개**를
만든다(런타임 교체 불가). 이를 팩토리로 전환한다.

- **`LlmClientFactory` / `EmbeddingClientFactory`** — 현재 설정을 읽어 **요청 시점에** 맞는 클라이언트를
  반환한다(설정 해시로 캐시해 매 호출 재생성 방지). 키 없으면 stub 폴백(조용한 실패 금지 로그).
- **capability 검증** — 설정 저장 시 역할별 허용 provider 만 통과(§2.1). 위반은 400.
- **차원 통일** — 모든 임베딩 클라이언트가 1024 로 요청(§2.2).
- **모듈 경계** — 새 모듈 `com.recall.settings` 가 설정을 소유. 팩토리(llm 모듈)가 `SettingsService` 를
  읽는다(settings → llm 의존 없음, 순환 없음). 재색인 오케스트레이션은 임베딩 쓰기가 있는
  store/review 쪽 `ReindexService` 를 settings 가 호출한다.

## 4. 데이터 모델

Flyway 새 마이그레이션으로 **단일 행** `model_setting` 테이블(단일 사용자 전제 — `user_id` 없음).

| 컬럼 | 설명 |
|------|------|
| `chat_provider` | anthropic \| openai \| google |
| `chat_model` | 모델 ID |
| `chat_api_key_enc` | **AES-GCM 암호문** (§7) |
| `embedding_provider` | openai \| voyage \| google |
| `embedding_model` | 모델 ID |
| `embedding_api_key_enc` | **AES-GCM 암호문** (§7) |
| `embedding_status` | READY \| REINDEXING \| FAILED |

- 스키마는 Flyway 소유(엔티티로 스키마 만들지 않음). 기존 마이그레이션 수정 금지 — 새 버전 추가.

## 5. 재색인 흐름 (임베딩 변경 시)

발동 방식은 **A안 = 즉시 자동 재색인**(UI 확인 다이얼로그 후).

**동기 구간 (PUT 요청-응답 안):**
1. capability 검증(§2.1)
2. **test-before-save** — 새 임베딩 설정으로 프로브 문자열 1회 임베딩. 실패(키 오류·차원 불일치)면
   **설정 저장 거부(400)** → 깨진 설정으로 진입 안 함(반쪽 인덱스 방지).
3. 설정 DB 저장(키는 암호화) + `embedding_status = REINDEXING`
4. 재색인 잡을 던지고 **즉시 응답 반환**

**비동기 구간 (@Async 잡 — 저장 경로 성격):**
5. 활성 memory 각각에 대해 보존된 구조화 표현(`searchReps.embeddingTexts`)을 **새 클라이언트로
   재임베딩** → `memory_embedding` 덮어쓰기. (덮어쓰는 건 원본이 아니라 파생 인덱스 — 원문
   캡처·구조화 memory 는 보존되므로 "삭제 대신 상태보존" 위반 아님)
6. 완료 시 `embedding_status = READY`, 실패 시 `FAILED`(실패 memory 는 조용히 넘기지 않고 표시).

**재색인 중 검색 (A안 확정):**
- 벡터 채널만 끄고 **BM25(키워드) 검색은 유지**(재색인과 무관, 결과 정확). graceful degradation.
- 상태를 응답/UI 에 노출("재색인 중 — 키워드 검색만 가능")하면 되고, 전면 차단은 하지 않는다.
- 완료 시 벡터 채널 복귀.

## 6. REST API

새 모듈 `com.recall.settings`. 엔드포인트 3개.

### `GET /api/settings/models` — 현재 설정
```json
{
  "chat":      { "provider": "anthropic", "model": "claude-opus-4-8", "apiKeyConfigured": true },
  "embedding": { "provider": "openai", "model": "text-embedding-3-small",
                 "apiKeyConfigured": true, "status": "READY" }
}
```
- 키는 **평문 반환 금지** — `apiKeyConfigured`(불리언)만.

### `PUT /api/settings/models` — 변경
```json
{ "chat":      { "provider": "openai", "model": "gpt-...", "apiKey": "sk-..." },
  "embedding": { "provider": "voyage", "model": "voyage-4-lite", "apiKey": "..." } }
```
- `apiKey` **생략/빈값 = 기존 키 유지**, 값 있으면 교체.
- 처리: capability 검증 → 임베딩 test-before-save → 저장 → (임베딩 변경 시) 자동 재색인(§5).

### `GET /api/settings/models/catalog` — UI 드롭다운용 정적 카탈로그
- provider별 capability(chat/embedding 가능 여부) + 선택 가능 모델 목록.
- 프론트가 이걸로 불가능 조합을 **아예 안 보이게** 막는다.

## 7. LLM/비밀 시큐어코딩 (cross-cutting, 필수)

키는 사용자 콘텐츠가 아니라 **자격증명**이므로 마스킹-우선과 별개로 추가 규칙을 적용한다. (backend/
CLAUDE.md "LLM/비밀 시큐어코딩" 규칙과 동일 — 위반은 🔴 치명.)

1. **저장 시 암호화(at-rest)** — DB 키는 평문 금지. **AES-GCM**, 마스터키는 env `RECALL_SECRET_KEY`.
2. **fail-closed** — `RECALL_SECRET_KEY` 없으면 키 DB 영속 거부. env 주입 키로만 동작.
3. **로그 금지** — 키를 로그·예외·스택·`toString()` 에 남기지 않는다.
4. **클라이언트 반환 금지** — 설정 API 는 `apiKeyConfigured` 만.
5. **전송 최소화** — 키는 의도한 provider 로만(HTTPS Authorization). 그 외로 안 나감.
6. **base-url 검증** — 오버라이드는 https 스킴만 허용(SSRF 여지 축소).
7. **capability 검증** — 역할 지원 provider 만 경계에서 허용.

## 8. 프론트 설정 화면

새 페이지 `SettingsPage`(기존 `pages/` + `store/RecallProvider` + `api/adapter` 패턴, frontend/CLAUDE.md 준수).

- **두 그룹**: 채팅(provider→model→키), 임베딩(provider→model→키 + 상태 배지).
- **불가능 조합 제외** — catalog 가 역할별 provider 만 내려주므로 UI 에 안 뜸.
- **키 입력 UX** — `apiKeyConfigured=true` 면 placeholder "설정됨(변경하려면 입력)", 빈값 = 유지.
- **저장** → PUT. 임베딩 변경 시 확인 다이얼로그 "전 메모리 재색인합니다. 계속?".
- **재색인 중** — status 폴링, 배너 "재색인 중 — 키워드 검색만 가능"(§5), 완료 시 해제.
- **에러**(400: 조합·프로브·키) → `useToast` 표시.

## 9. 불변 원칙 게이트

- 자동 저장 없음 — 설정 변경은 memory 를 만들지 않음(재색인은 인덱스 갱신). ✅
- 마스킹 우선 — 임베딩 텍스트는 이미 마스킹된 구조화 표현에서 나옴. 키는 별도 시큐어코딩(§7). ✅
- 삭제 대신 상태보존 — 재색인은 파생 인덱스만 덮어씀, 원문·memory 보존. ✅
- 결정론 단계 LLM 금지 — 본 기능은 인프라 설정, 검색 결정론(P/R/W) 불변. ✅
- 근거 없는 생성 금지 — 재색인 중 BM25 근거에 매인 답변만. ✅
- 조용한 실패 금지 — test-before-save·재색인 상태(READY/REINDEXING/FAILED)·격하 배너로 노출. ✅

## 10. Eval / 테스트

- **capability 검증**(결정론) — 허용/불가 조합 순수함수 테스트(불가 조합 → 400).
- **응답 파싱·차원검증**(결정론) — provider별 임베딩 응답 → float[1024], 차원 불일치 예외
  (기존 `EmbeddingResponseParsingTest` 패턴, Google 추가 시 확장).
- **재색인 상태 전이** — READY→REINDEXING→READY/FAILED 및 실패 노출.
- **🔴 시큐어코딩 회귀** — 키가 GET 응답/로그에 안 나오는지, `RECALL_SECRET_KEY` 없을 때 평문
  저장 안 하는지(fail-closed) 케이스는 병합 게이트로 유지.

## 11. 범위 / 단계화

이 설계는 하나의 기능이지만 구현은 단계로 나눈다(구현 계획에서 상세화).

1. **백엔드 기반** — `model_setting` 마이그레이션 + settings 모듈(엔티티/리포지토리/서비스) +
   팩토리 전환 + capability 검증 + 시큐어코딩(암호화 유틸) + REST API 3종.
2. **Google 임베딩 클라이언트** — 임베딩 provider 3종 완성(현재 OpenAI·Voyage 존재).
3. **재색인 잡** — @Async ReindexService + 상태 전이 + 검색 BM25 격하.
4. **프론트 설정 화면** — SettingsPage + catalog 드롭다운 + 재색인 배너/폴링.

### 범위 밖(후속)
- 차원 상향(1536+) — Eval 로 개선 측정 후 별도 마이그레이션.
- provider별 세부 파라미터(temperature 등) 튜닝 UI.
- 채팅 모델 test-before-save(현재는 임베딩만 프로브. 채팅은 capability+키 검증).
