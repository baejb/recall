# Recall — AI PRD v2

> 요즘IT "AI PRD" 프레임워크(8항목 · Eval Plan · 비용모델 · 정합성)를 Recall 설계에 적용.
> **v2:** v0708 "질문 유형 기반 검색"을 반영해 조회 경로 세부화.

`허용 범위 정의` · `단계별 Eval Unit` · `Query Classifier 다차원` · `메모리 5종` · `자동 저장 없음 · 승인 게이트` · `BYO key · 비용 통제`

> **불변 원칙.** "무엇이 일어나야 하는가"(결정론)가 아니라 **"어떤 출력이 받아들여질 만한가"(범위)** 를 단계별로 정의한다. 자동 저장 없음 · 승인 게이트 · 민감정보 마스킹 우선 · 삭제 대신 상태 보존.

> **v2 변경 요지** — 단일 `S1 Intent` → 다차원 Query Classifier + Search Planner + Weighted Ranker + Reranker · 메모리 2종 → 5종.

---

## 00. LLM / 알고리즘 단계 지도

결과를 좌우하는 지점을 저장·조회 경로로 나누고, 각 단계 성격(🔵 확률적 LLM / 🟢 결정적 튜닝)을 명시한다. 성격에 따라 Eval 방식이 다르다 — 확률적=허용범위 판정, 결정적=라벨셋 지표.

### 조회 경로 (Query Pipeline) — v2에서 세부화

| # | 단계 | 입력 → 출력 | 성격 |
|---|------|-------------|------|
| C | **Query Classifier** | 발화 → 다차원 라벨(route·novelty·domain·ts_subtype·entities) | 🔵 확률적(LLM) |
| P | **Search Planner** | 라벨 → {검색대상, 방식별 가중치, metadata filter} | 🟢 결정적(라우팅표) |
| R | **Hybrid Retriever** | 질문+plan → 후보군(4채널 병렬) | 🟢 결정적 |
| W | **Weighted Ranker** | 4채널 후보 → RRF + boost/penalty 정렬 | 🟢 결정적(가중치) |
| RR | **Reranker** | 질문+상위후보 → 재정렬 점수 | 🔵 확률적 |
| A | **Answer Composer** | 최종 후보 → Intent별 재구성 답변 + 근거 | 🔵 확률적(LLM) |

### 저장 경로 (Store Pipeline) — v1 계승

| # | 단계 | 입력 → 출력 | 성격 |
|---|------|-------------|------|
| M0 | **마스킹** | 원문 → 민감정보 마스킹 + masked_spans | 🟢 결정적(패턴) — LLM 이전 |
| S2 | **구조화 추출** | Capture → 5유형 중 하나의 JSON | 🔵 확률적 |
| S3 | **긴맥락 Map-Reduce** | 대용량 대화 → 필터·클러스터·병합 | 🔵 확률적 |
| S4 | **유사 판정 + 모순 탐지** | 신규 후보+유사 Memory → verdict | 🔵 확률적 |

> **핵심 재정의 (v1→v2).** v1의 "S1 Intent 분류"는 저장/조회 갈림 + 조회 intent만 판단. v2의 **Query Classifier(C)** 는 여기에 **신규/회상 · 지식/트러블슈팅 · 트러블슈팅 5세부 · 엔티티 추출** 을 더한 다차원 분류기. 분류를 전략으로 바꾸는 **Search Planner(P)** 를 명시 단계로 승격.

---

## 01. 기능 개요 — 왜 AI가 가장 잘 푸는가

개발자가 해결한 트러블슈팅·학습 지식·프로젝트 결정이 대화 로그에 흩어져, 재발 시 "전에 어떻게 풀었지?"를 처음부터 다시 하게 되는 문제를 푼다.

- **질문 이해 → C**: "이 429가 예전 그 429냐"는 키워드 매칭이 아니라 성격 판단
- **구조화 추출 → S2**: 해결 사건/Fact/결정을 비정형 대화에서 스키마로
- **동일성 판정 → S4·RR**: 문제·근본원인·환경의 의미 대조
- **재구성 답변 → A**: 나열이 아니라 "당시 vs 지금" / 최신 정리

> **AI를 위한 AI 방지.** 마스킹은 LLM 이전 패턴 탐지(M0). **P·R·W는 결정적으로 유지** — 재현·감사·비용 통제. LLM은 모호성이 본질인 단계(C·RR·A·S2·S3·S4)에만.

---

## 02. 입출력 명세

### 2.1 시스템 경계

| 방향 | 형태 | 제약 |
|------|------|------|
| **입력(저장)** | 텍스트(붙여넣기·대화·문서) | `chat\|markdown\|log\|plain`, 한/영, 토큰 상한 T_max |
| **입력(조회)** | 자연어 질문 | 짧은 회상/비교 ~ 긴 에러 로그 포함 |
| **출력(조회)** | SSE 스트리밍 + 근거 링크 | 답변 문장에 memory_id/capture_id 근거 필수 |
| **출력(저장)** | 검토 대기함 후보(JSON) | 승인 전 DB 미반영 |

### 2.2 단계별 입출력 계약 (structured output 강제)

**C — Query Classifier** (다차원, function calling)

```jsonc
{
  "route": "store | query",
  "novelty": "new | recall",              // 신규 vs 회상
  "domain": "knowledge | troubleshooting | project | decision | command",
  "ts_subtype": "error_msg | symptom | attempt_recall | solution_recall | compare | null",
  "query_intent": "FIND | STATUS | COMPARE | EVOLUTION | ANSWER | null",
  "entities": {
    "project": "string|null", "components": ["string"],
    "error_signature": "string|null", "tech": ["string"]
  },
  "confidence": { "route": 0.0, "novelty": 0.0, "domain": 0.0, "ts_subtype": 0.0 }
}
```

**P — Search Planner** (결정적 라우팅 테이블, LLM 아님)

```jsonc
{
  "targets": ["troubleshooting","command_code"],   // 검색할 메모리 유형
  "channel_weights": { "exact": 3.0, "raw_bm25": 2.0, "memory_bm25": 2.0,
                       "memory_vector": 1.2, "raw_vector": 1.0 },
  "boosts": { "project": 1.5, "component": 1.3, "resolved": 1.3, "recency": 1.1 },
  "penalties": { "stale": true, "unresolved": true, "low_conf_summary": true },
  "metadata_filter": { "project": "prollmops", "status": null },
  "use_reranker": true,
  "raw_search_required": false            // 회상 질문이면 true(원문 필수)
}
```

- **R/W** → `[{memory_id, channel_scores{}, rrf_score, final_score, rank}]`
- **RR** → `[{memory_id, rerank_score}]`
- **A** → 답변 + `citations[]`
- **S2** → 5유형 JSON
- **S4** → `{verdict, target_memory_id?, rationale}`

---

## 03. 시스템 프롬프트 초안 (LLM 단계별)

- **C — 분류**: 다차원 독립 판단 + 확신도. 에러 문자열→error_msg 강하게 · "결국/최종"→solution_recall · "같은 거야?"→compare · "전에 물어봤나?"→recall. 낮은 확신도는 단정 말고 confidence로.
- **RR — 리랭크**: 질문↔후보 실제 관련도. 트러블슈팅은 **증상·근본원인·해결책 적용 가능성** 기준. 표면 키워드 겹침 아님. 환경 차이는 감점 사유로 명시.
- **A — 답변**: Intent별 현재 상태 재구성, 나열 금지. **저장 안 된 결론 생성 금지**. 회상=물어본 적 有/無+당시 결론, 비교=같은/다른 점+추가 확인 필수.
- **S2 · S4 — 추출/판정**: S2: 유형 라우팅 후 스키마로, **실패 시도 버리지 말 것**. S4: 유사도 아닌 근본원인·에러시그니처·환경·모순 근거, **자동 덮어쓰기 금지**.

---

## 04. 메모리 유형 (2종 → 5종) `v2 신설`

유형마다 저장 스키마와 검색 표현(임베딩/키워드)이 다르다. "무엇을 임베딩하나"가 연관성의 절반을 결정한다.

| 유형 | 저장 핵심 필드 | 검색 표현 | 주 검색 방식 |
|------|----------------|-----------|--------------|
| **📘 knowledge** | topic · summary · keywords · facts[] · document | document_embedding + fact_embedding | vector 중심 · BM25 보조 · recency |
| **🔧 troubleshooting** | symptom · error_message · environment · attempts[] · root_cause · final_solution · status | problem/solution 이중 벡터 + error_signature | exact · BM25 · vector · metadata · reranker |
| **🗂 project_context** | project · component · summary · decisions[] | summary_embedding + project/component(메타) | metadata filter 강함 · BM25 강함 |
| **📌 decision** | decision · reason · alternatives[] · status | decision_embedding + status(메타) | BM25 · metadata · recency · status boost |
| **⌨ command_code** | language_or_tool · command · description · context | command(정확 토큰) + description_embedding | exact 매우 중요 · BM25 · vector 낮음 |

> **임베딩 원칙(v1 유지).** 원문이 아니라 **추출된 검색 표현** 에 임베딩. 트러블슈팅=문제/해결 이중 벡터, 지식=fact/document 분리. command_code·error_signature 같은 **고유 토큰은 정확 키워드 매칭** 이 벡터보다 강함 → 하이브리드 필수. 데이터 모델: `memory.type` enum + `memory_embedding.kind` 확장만으로 유형 추가.

---

## 05. 품질 기준 — "이 정도면 합격" (단계별)

| 단계 | 합격 기준(정성) | 목표 지표(초기 기준선) |
|------|------------------|------------------------|
| **C route/novelty** | 저장/조회·신규/회상 오분류가 흐름을 안 깸 | `route ≥ 0.95, novelty ≥ 0.90` |
| **C domain** | 5유형 라우팅 정확 | `domain ≥ 0.88` |
| **C ts_subtype** | 5세부(특히 error_msg↔symptom, solution_recall) | `ts_subtype ≥ 0.85` |
| **C entities** | project/component/error_signature 추출 | `error_sig 재현율 ≥ 0.9` |
| **P plan** | 라벨→전략 매핑이 규칙표와 100% 일치 | `커버리지 100%, 미정의 0` |
| **R+W 검색** | 관련 Memory가 상위에 | `Recall@5 ≥ 0.85, MRR ≥ 0.8` |
| **RR 리랭크** | 동일성 비교에서 실제 관련 후보 최상단 | `비교 nDCG@5 ≥ 0.85` |
| **A 답변** | 근거 있는 문장만, 유형별 형식 준수 | `citation ≥ 0.95, 환각 ≤ 0.02` |
| **S2 추출** | 유형·필드 정확, 모든 시도 보존 | `필드 ≥ 0.9, attempts 재현 ≥ 0.9` |
| **S4 판정** | 재발↔신규 정확, 충돌 미탐 0 | `정확도 ≥ 0.85, silent overwrite = 0` |

---

## 06. 실패 정의 — 무엇이 나오면 "실패"인가

| 심각도 | 실패 유형 | 단계 |
|--------|-----------|------|
| 🔴 치명 | **민감정보 유출**(마스킹 전 원문이 LLM/인덱스/로그에) | `M0·S2·S3` |
| 🔴 치명 | **충돌 사실 자동 덮어쓰기** | `S4` |
| 🔴 치명 | **근거 없는 생성**(저장 안 된 결론을 사실처럼) | `A` |
| 🟠 중대 | **error_msg를 symptom으로 오분류** → exact 검색 누락 → 과거 케이스 miss | `C` |
| 🟠 중대 | **회상 질문을 신규로 처리** → 원문 검색 생략, "예전에 있음" 놓침 | `C·P` |
| 🟠 중대 | **solution_recall인데 unresolved 케이스 상단** 노출 | `P·W` |
| 🟠 중대 | 실패 시도(attempts) 유실 · 재발을 신규로 분리 · 조용한 truncation | `S2·S3·S4` |
| 🟡 경 | domain 오분류 · Top-k 밖 miss · Planner 미정의 폴백(로그) | `C·R·W·P` |

> **🔴 3종은 Eval 필수 케이스이자 릴리스 차단 게이트.** 🟠는 유형별 회귀 셋에 반드시 존재. (에어캐나다 사고 = "무엇을 답하지 말아야 하는가"가 PRD에 없어서.)

---

## 07. 평가 계획 (Eval Plan) — AI PRD의 심장

### 7.1 단계별 Eval 셋 (스프레드시트로 시작)

단계마다 한 시트. 각 단계 20~30 케이스 시작 → 실패 발견 시 한 줄씩 추가 → 6개월 뒤 단단한 셋이 자산.

**C(Classifier) 시트** — 다차원이라 축별 정답 컬럼

| 입력 | route | novelty | domain | ts_subtype | 합격 조건 |
|------|-------|---------|--------|------------|-----------|
| "RateLimitException 429 전에 물어봤어?" | query | recall | ts | error_msg | 모든 축 일치 |
| "그 docker 권한 문제 결국 어떻게 해결했더라?" | query | recall | ts | solution_recall | subtype 필수 |
| "RRF가 뭐야?" | query | new | knowledge | null | domain=knowledge |
| "컨테이너가 자꾸 죽어" | query | new | ts | symptom | **error_msg면 불합격** |

- **P 시트**: 결정적 → "분류 라벨 → 기대 plan" 일치(규칙표 테스트).
- **RR 시트**: 비교 질문에 정답 랭킹 라벨로 nDCG 측정.

### 7.2 평가 피라미드 — 케이스마다 층을 미리 지정

```
사람 평가          · 🔴/새 패턴/golden set
    ↑
LLM-as-a-Judge     · RR/A/S2 faithfulness
    ↑
규칙 기반          · C라벨/P규칙표/Recall@k/마스킹0
```

일상은 규칙+Judge로 빠르게, 🔴·회귀 기준점만 사람. C=라벨 정확도 자동채점 · P=순수함수 테스트 · R+W=Recall@k/nDCG · M0=민감패턴 잔존 0(🔴).

### 7.3 회귀 테스트 — "프롬프트 수렁" 방지

- **프롬프트 변경**: C/RR/A/S2/S4 중 어느 프롬프트든 변경 시 그 단계 Eval 셋 전체 재실행, 하락 케이스 자동 리포트
- **가중치 변경**: P/W 가중치 변경 시 전체 검색 라벨셋(Recall@k·nDCG) 재실행 — 한 유형 튜닝이 다른 유형 깨는지 감시

> **CI 게이트.** 🔴 케이스 1건이라도 실패 시 병합 차단. **정합성 체크**: "해결/재발/충돌" 정의(S4 verdict) = 품질기준 = 성공지표 = 비용/과금 단위가 하나의 정의 공유.

---

## 08. 모니터링 계획 (출시 후)

| 관찰 대상 | 지표 | 알람 |
|-----------|------|------|
| **분류 품질** | 재질문율, 수동 정정 빈도 | 정정↑ → C 회귀 의심 |
| **검색 적중** | "근거 열람" 비율, 0-result 비율 | 0-result↑ → P 필터 과함 |
| **리랭크** | 비교 질문 상단 채택률 | 채택률↓ → RR 저하 |
| **추출 품질** | 승인율·수정률 | 승인율 급락 → S2 회귀 |
| **판정 안전** | conflict 발생·해소, supersede 취소율 | 취소↑ → S4 과잉 |
| **답변 안전** | 근거 없는 문장/환각 신고 | 🔴 즉시 |
| **비용** | Capture당·조회당 토큰, 월 비용 | 예산 임계 초과 |

**Eval 재실행 주기**: 프롬프트/모델/가중치 변경 시(필수) + 월 1회.

---

## 09. 비용 모델 (셀프호스트 · BYO key)

셀프호스트 · 사용자 자기 LLM API 키 → 비용 통제가 관건. "쓸 때마다 토큰 비용 발생 → 설계와 분리 불가"는 그대로.

- **비용 단위**: Capture 1건(S2+임베딩) · 조회 1건(C + R/W + RR + A)
- **v2 비용 레버**: **P/R/W가 결정적** → 조회 1건에서 LLM은 C·RR·A 3곳뿐. RR은 상위 k에만(use_reranker 플래그). S3 필터에 값싼 모델 + segment_index 캐시. 토큰 예산 분기(≤8k 단일패스).

> **정합성.** 향후 성과기반 과금 전환 시 "해결 케이스" = S4 `recurred/resolved` 정의를 그대로 과금 단위로.

---

## 10. 정합성 체크 (기능 · Eval · 지표 · 비용)

| 개념 | 정의 위치 | 품질기준 | 성공지표 | 비용/과금 |
|------|-----------|--------------|--------------|---------------|
| **회상 질문** | `C.novelty=recall` | novelty 정확도 | 재질문율 | 조회 1건 |
| **재발** | `S4=recurred` | 판정 정확도 | 재발 추적 | 해결 케이스 |
| **해결** | `status=resolved` | citation faithfulness | 재사용률 | 해결 케이스 |
| **충돌** | `S4=conflict` | silent overwrite=0 | supersede 취소율 | — |
| **동일 문제** | `RR 판단` | 비교 nDCG | 비교 채택률 | — |

> **다섯 개념이 단계·Eval·지표·비용에서 같은 정의를 공유** 해야 PRD가 하나의 시스템으로 작동. 하나만 성격이 달라지면 무너진다.

---

## 11. 릴리스 게이트

1. 🔴 실패 3종(민감정보 유출 / 충돌 자동덮어쓰기 / 근거 없는 생성) Eval **전건 통과** — 하나라도 실패 시 차단
2. C 다차원 분류 각 축 품질 기준 목표 달성(특히 error_msg↔symptom, recall 판별)
3. P 라우팅 테이블 커버리지 100%(미정의 조합 0, 폴백은 로그로 가시화)
4. R+W Recall@5 / RR 비교 nDCG 목표 달성
5. 회귀: 직전 릴리스 대비 하락 케이스 0(또는 승인된 트레이드오프만) — 가중치 튜닝 포함
6. 조용한 truncation 고지 동작 확인

---

## 부록 A. v1 → v2 변경 요약

| 항목 | v1 | v2 |
|------|-----|-----|
| **조회 분류** | 단일 S1(저장/조회 + intent) | **다차원 Classifier(C)**: route·novelty·domain·ts_subtype·entities |
| **전략 수립** | 암묵적 | **Search Planner(P)** 명시 단계(결정적 라우팅표) |
| **정렬** | RRF 언급 | **Weighted Ranker(W)**: RRF + boost/penalty 공식화 |
| **리랭크** | 로드맵 5단계 | **Reranker(RR)** 를 비교 질문 필수 단계로 승격 |
| **메모리 유형** | 2종 | **5종**(+project_context/decision/command_code) |
| **Eval** | 단계별 | C **축별 채점** + P **규칙표 테스트** + RR **nDCG** 추가 |

---

**범례** — 🔵 확률적(LLM): C·RR·A·S2·S3·S4 · 🟢 결정적: M0·P·R·W · 🔴 릴리스 차단 게이트 3종
