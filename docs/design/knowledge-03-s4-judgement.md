# knowledge S4 판정 — 유사 후보 대조(재발/보완/충돌)

> 커밋: `ae05faa` · 상태: 완료

## 왜

S4 판정이 stub이라 항상 NEW였다: `KnowledgeJudge`는 파라미터를 무시하고 NEW를 반환하고,
`StorePipeline`은 `judge(structured, Map.of())`로 유사 기존 memory를 전혀 넘기지 않았다. 그래서
같은 문제를 또 겪어도 매번 새 기억으로 쌓이고(재발/충돌 미탐지), review_queue의 memory_id·
judge_reason 컬럼은 늘 NULL이었다. 신규 추출이 기존 기억과 같은지/충돌하는지 판정한다.

## 무엇을

- **유사 후보 조달** (`SimilarMemoryFinder`): 신규 추출의 document 임베딩 코사인(τ_sim) 최상위,
  없으면 BM25 폴백. 저장 경로는 "문서 vs 문서"라 `embedDocument` 사용.
- **판정** (`KnowledgeJudge` 재작성): LLM으로 proposed·existing 사실 대조 →
  NEW/RECURRENCE/SUPPLEMENT/CONFLICT + rationale. 파싱 실패·stub이면 결정론 fallback.
  프롬프트는 `resources/prompts/knowledge-judgement.md`.
- **배선**: `StorePipeline`이 유사 후보→판정→`ReviewItem`(targetMemory·judgeReason)까지 흘리고,
  `ReviewItemResponse`에 targetMemoryId·judgeReason·memoryType 노출(프론트 "이미 기억에
  있어요" 배너 대응).
- **BM25 OR 매칭 수정**(`MemorySearchStore.searchByKeyword`): plainto_tsquery의 AND를 lexeme
  OR 결합으로. 한국어 조사 차이로 매칭 안 되던 문제(슬라이스 2 검색도 함께 개선).

## 설계 판단

- **targetMemoryId는 판정 전략이 아니라 파이프라인이 채운다** — judge는 existing 필드만 받아 id를
  모른다. judge=유형별 판정, 파이프라인=후보 id 배선(관심사 분리).
- **fallback verdict = SUPPLEMENT** — 후보는 있으니 NEW 아님, CONFLICT는 과함, 사람 검토 유도
  (자동 덮어쓰기 금지). LLM stub이면 항상 이 경로.
- **τ_sim = 0.75** 상수(라벨셋 fit 튜닝 대상, 후속에 `@ConfigurationProperties`로 뺄 여지).
- Flyway 변경 없음 — review_queue의 memory_id·judge_reason·memory_type 컬럼은 이미 V2·V3에 존재.

## 검증

단위테스트(KnowledgeJudge 6 — 파싱·fallback·empty→NEW·unknown verdict 방어) · 부팅 스모크
(유사 지식 재입력 → verdict=SUPPLEMENT·targetMemoryId 노출, 무관 지식 → NEW). LLM stub이라
fallback 경로, 유사 탐지는 BM25로 실동작.

## 범위 밖 / 후속

승인 시 재발(RECURRENCE) 반영(기존 memory hits++/상태 전이), 실제 LLM 연동 시 verdict 품질 Eval,
τ_sim·판정 프롬프트 튜닝, 다차원 분류(C).
