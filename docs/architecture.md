# Recall 백엔드 아키텍처 (설계 결정 기록)

> 이 문서는 **왜 이 구조인가(선택 이유·판단 기준)** 를 남긴다. 강제되는 **규칙**은
> `backend/CLAUDE.md`에, 기능 근거는 `docs/recall_ai_prd.md`에 있다.

## 결정 요약

- **패턴**: 실용 계층형 **모듈러 모놀리스** + **LLM/임베딩 경계에만 경량 헥사고날(포트+어댑터)**.
- **확장**: 메모리 유형(type)별 **전략(Strategy) SPI** 로 파이프라인을 확장한다.
- **분담**: Phase 0(공유 walking skeleton + SPI 계약) → Phase 1(유형별 전략 병렬 구현).

---

## 용어 — 파이프라인 단계 약어

이 문서와 코드에 나오는 단계 약어를 한 번에 편다(자세한 근거는 `docs/recall_ai_prd.md`). 약어를 그대로
쓰되, 처음 보는 사람이 여기서 뜻을 찾을 수 있게 한다.

| 약어 | 단계(뜻) | 성격 | 경로 |
|------|----------|------|------|
| M0 | 마스킹 — 민감정보 가리기 | 결정적 | 저장 |
| S2 | 구조화 추출 — 대화 → 유형별 JSON | 확률적(LLM) | 저장 |
| S3 | 긴맥락 Map-Reduce — 대용량 대화 필터·클러스터·병합 | 확률적(LLM) | 저장 |
| S4 | 판정 — 재진입/중복/모순 판단 | 확률적(LLM) | 저장 |
| C | 분류(Classify) — 질문 유형 판정 | 확률적(LLM) | 조회 |
| P | 플래너(Plan) — 검색 계획·플래그 | 결정적 | 조회 |
| R | 검색(Retrieve) — 채널별 후보 수집 | 결정적 | 조회 |
| W | 가중치(Weight) — 채널 결과 결합 | 결정적 | 조회 |
| RR | 리랭크(Rerank) — 상위 후보 재정렬 | 확률적(LLM) | 조회 |
| A | 답변(Answer) — 근거 기반 답 생성 | 확률적(LLM) | 조회 |

- 저장 경로: `M0 → S2 → S3 → S4`
- 조회 경로: `C → P → R → W → RR → A`
- 결정적(M0·P·R·W)은 재현·감사·비용 통제를 위해 LLM을 쓰지 않는다. LLM은 모호성이 본질인 단계에만.

---

## 1. 아키텍처 패턴 — 왜 "실용 계층형 + 선택적 포트"인가

### 후보

- **A. 실용 계층형 모듈러 모놀리스** (채택) — 계층 `controller → service → repository`, 포트는 꼭
  필요한 경계에만.
- **B. 풀 헥사고날(포트&어댑터)** — 모든 인프라 경계를 포트 뒤로.
- 클린/오니언 아키텍처 — 이 규모엔 오버스펙이라 제외.

> A와 B는 이분법이 아니다. 둘 다 "모듈러 모놀리스 + 레이어드"이고, 차이는 **"포트를 어디에 얼마나
> 두느냐"** 하나다. A는 꼭 필요한 경계(LLM/임베딩)에만, B는 모든 인프라 경계에 둔다.

### 판단 기준 — 경계마다 "포트를 둘까 말까"

**포트를 둔다** (아래 중 하나라도 참):

1. **실제 구현이 2개 이상**이거나 벤더 교체 예정 (LLM provider, 강/저가 모델)
2. **변덕스러운 외부 의존** — 내가 통제 못 하고 자주 바뀜 (외부 API)
3. **테스트가 아픔** — 느림/비결정적/비용(네트워크·LLM·시계·랜덤) → fake 주입 필요
4. **격리할 가치 있는 도메인 로직**이 실재

**포트를 안 둔다** (구체 클래스): 위가 모두 거짓 — 단일 구현 영구(JPA CRUD), pass-through 로직,
`XxxImpl` 하나뿐인 껍데기.

### Recall 적용 결과

| 경계 | 기준 해당 | 결정 |
|------|-----------|------|
| `LlmClient` | 1·2·3 (provider·모델티어·LLM 테스트) | ✅ **포트** |
| `EmbeddingClient` | 1·2·3 | ✅ **포트** |
| search(pgvector/BM25) | 단일 구현(Postgres 고정), 결정적 | ❌ 구체/JPA |
| memory·review·capture 영속 | 단일 JPA 구현 | ❌ 구체 |
| 결정론 단계 P·R·W·M0 | 순수함수라 외부의존 0 → 이미 테스트 쉬움 | ❌ 순수 클래스 |
| 확률 단계 C·RR·A·S2… | LLM 포트에 의존하지만 자기 구현은 하나 | ❌ 자기 포트 불필요 |

→ 기준을 그대로 적용하면 **"LLM+임베딩만 포트" = A** 로 수렴한다.

### "요즘 헥사고날 덜 쓴다"는 말의 해석

- 맞는 부분: **도그마틱 풀 헥사고날/클린 아키텍처**(모든 것에 포트 + 매핑 레이어 잔뜩)는 세리머니
  과함으로 백래시 중.
- 오해인 부분: **핵심 아이디어(교체·테스트 필요한 경계만 의존성 역전)** 는 여전히 표준. 트렌드는
  "폐기"가 아니라 **실용적 선택적 포트 + 버티컬 슬라이스**로 이동 — 그게 곧 A다.

### 비대칭 비용 (결정적 근거)

- A로 시작 → 나중에 두 번째 구현이 생기면 → `Extract Interface` 리팩터(IDE 자동, 기계적, 싸다).
- B로 시작 → 안 쓰는 포트 걷어내기 → 매핑·어댑터까지 들어내야 해서 비싸다.

**추상화는 "두 번째 케이스가 실제로 나타날 때" 도입**한다. Recall은 2인·셀프호스트·단일 인바운드
(REST/SSE)·Postgres 고정 → B의 이득은 대부분 발생하지 않고 비용만 남는다.

---

## 2. 모듈 경계

```
com.recall
├─ common/    예외·전역핸들러·audit·설정·MemoryType·TypeStrategy·StrategyRegistry
├─ llm/       LlmClient·EmbeddingClient 포트 + 어댑터        [포트 — 유일하게 인터페이스]
├─ capture/   원문 저장(sync anchor) + M0 마스킹
├─ store/     저장 파이프라인(@Async): S2·S3·S4 오케스트레이션
├─ query/     조회 파이프라인(SSE): C·P·R·W·RR·A
├─ search/    하이브리드 채널(exact·bm25·vector)·RRF·Weighted·Rerank
├─ review/    검토 게이트·승인/반려
└─ memory/    Memory 엔티티·저장·상태전이·임베딩 인덱스
    └─ type/  유형별 전략 계약(SPI)과 구현을 한 곳에 모은다
        ├─ ExtractionStrategy · SimilarityJudgeStrategy · SearchRepresentation
        │       · PlanContribution · AnswerContribution   ← 계약(파이프라인이 호출)
        ├─ knowledge/        (담당: 지식 — 위 계약 구현)
        └─ troubleshooting/  (담당: 트러블슈팅 — 위 계약 구현)
```

- **유형별 전략 계약(SPI)은 `memory/type/`에 모은다** — 파이프라인 단계(store/query/search)가
  이 계약을 **호출**하고, 유형 패키지(knowledge/troubleshooting)가 **구현**한다. 계약과 구현을 한
  곳에 두어 "새 유형이 무엇을 구현해야 하는지"를 한눈에 본다. 의존 방향: `store/query/search →
  memory/type`(계약), `memory/type/knowledge → memory/type`(구현) — 순환 없음.
- **계층**: `controller → service → repository`. 역방향/횡단 호출 금지. 도메인 서비스는 웹/영속
  세부를 모른다.
- **패키지 = 모듈 경계**. 모듈 간은 public 서비스로만. 순환 의존 금지.

---

## 3. 파이프라인과 유형별 전략(SPI)

파이프라인(조회 `C→P→R→W→RR→A`, 저장 `M0→S2→S3→S4`)은 **공유**다. 유형은 **끼워넣는 전략만**
다르다.

| 확장점(SPI) | 단계 | knowledge | troubleshooting |
|-------------|------|-----------|-----------------|
| `ExtractionStrategy` | S2 | topic·facts·document | symptom·root_cause·attempts·status |
| `SearchRepresentation` | R | doc/fact 벡터 | 문제/해결 이중벡터 + error_signature |
| `SimilarityJudgeStrategy` | S4 | fact 대조 | 근본원인·모순 |
| `PlanContribution` | P | vector 중심 가중치 | exact·bm25·rerank 강 |
| `AnswerContribution` | A | 지식 정리용 근거·필드 | 당시 vs 지금 근거·필드 |

- Spring `Map<MemoryType, XxxStrategy>` 주입. 각 전략은 `supports(): MemoryType`로 **자가 등록**한다.
- PRD의 "`memory.type` enum + `memory_embedding.kind` 확장만으로 유형 추가"와 1:1 대응.

---

## 4. 확장성 가드레일 (이걸 지켜야 확장성이 유지된다)

1. **공유 코드에 `switch(MemoryType)` 금지.** 유형 분기는 전략 레지스트리로만. switch가 새면 새 유형
   마다 그 switch를 다 고쳐야 해 OCP가 깨진다.
2. **SPI는 "진짜 변동 축"에 둔다.** S2 추출·검색표현·S4 판정은 유형축이 명확. **답변(A)은 유형 ×
   query_intent 로 변하므로**, 공유 Composer가 intent를 처리하고 유형 전략은 **근거·필드만 기여**한다
   (순수 per-type 포매터로 묶지 않는다).
3. **임베딩은 제네릭 키 테이블** `memory_embedding(kind, vector)`. 유형별 벡터 컬럼을 `memory`에
   박지 않는다 → 새 표현은 스키마 변경이 아니라 **행 추가**로 끝난다.
4. **선택적 단계는 plan 플래그로.** 파이프라인에 `if(type==…)` 분기 대신 plan(`use_reranker`,
   `raw_search_required` 등)이 단계를 켜고 끈다.
5. **공유 접점은 enum 하나로 최소화.** 전략 자가 등록으로 새 유형 추가 시 중앙 등록 리스트를 편집할
   필요가 없게 한다(분담 충돌면도 축소).

### 확장 시나리오 검증

| 미래 변경 | 비용 | 판정 |
|-----------|------|------|
| 새 type 추가(project_context·decision·command_code) | enum 1줄 + 새 `type/xxx/` + Flyway 대역 + Eval. 파이프라인·타 type 무수정 | ✅ OCP |
| 새 type이 특정 단계 스킵 | plan 플래그(데이터) | ✅ |
| 새 type이 완전 새 단계 필요 | 파이프라인 수정 | 🟡 드묾, 정당 |
| 새 임베딩 표현 | `memory_embedding` 행 추가 | ✅ (가드레일 3 전제) |
| LLM provider/모델티어 추가 | 어댑터 추가 | ✅ |

---

## 5. 작업 분담

정적 분할 대신 **단계(phase)** 로 나눠 "공유 레일 = 병목이자 충돌면" 문제를 푼다.

- **Phase 0 — 공유 walking skeleton (환경세팅 담당)**: 파이프라인 오케스트레이터(호출 순서만) +
  **SPI 인터페이스** + `MemoryType` enum + LLM 포트 + capture/review/memory 최소 영속. 전략은 stub.
  요청이 끝까지 흐르고 컴파일되면 완료 → **공유 계약이 서는 순간 병렬 병목 해제.**
- **Phase 1 — 병렬**: `knowledge`(지식) / `troubleshooting`(트러블슈팅) 전략 세트를 각자 자기 type
  패키지에서 구현. 공유 파일을 건드리지 않아 충돌 0.
- **공유 레일 심화**(search 튜닝·W·RR 등)는 먼저 필요해진 사람이 PR로 올려 리뷰 후 반영.

### 병렬 작업 계약

1. **SPI 인터페이스를 초기에 확정** — 팀원은 안정된 계약에 맞춰 병렬 구현.
2. **Flyway append-only + 버전대역 예약** — 공유 `V1~V9`, knowledge `V10~V19`,
   troubleshooting `V20~V29`. 기존 파일 수정 금지.
3. **`MemoryType` enum·SPI 변경은 코디** — 유일한 공유 파일이므로.

---

## 참고

- 기능 근거: `docs/recall_ai_prd.md` (파이프라인 단계 C·P·R·W·RR·A·M0·S2·S3·S4, 메모리 5유형)
- 강제 규칙: `backend/CLAUDE.md`
