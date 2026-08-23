# troubleshooting 유형 추가 — SPI 5종 + 저장 경로 유형 라우팅

> 상태: 완료 · 기준 문서: `docs/recall_ai_prd.md` §00·§02.2·§03·§04·§06, `docs/architecture.md`

## 왜

`MemoryType`에 `TROUBLESHOOTING` 값은 있었지만 구현이 하나도 없었다. 그 결과:

- 저장 경로는 `StorePipeline.classify()`가 **항상 `KNOWLEDGE`를 반환**하는 TODO였고(유형 라우팅 부재),
- 조회 경로의 분류(C)는 등록된 유형이 1개뿐이라 **LLM을 부르지 않고 격하**되는 상태로 잠들어 있었고,
- `PlanContribution`의 "유형별 채널 가중치"는 값이 하나뿐이라 사실상 효력이 없었다.

PRD의 1순위 유형(개발자의 트러블슈팅 회상)을 붙여 이 세 장치를 동시에 실제로 켠다.

## 무엇을

knowledge 유형이 슬라이스 3개(S2 → 검색 → S4)로 나뉘어 들어온 것과 달리, 이번엔 **파이프라인 단계별
SPI 5종을 한 슬라이스로** 넣는다. 계약(SPI)·프롬프트 규약·테스트 골격이 이미 knowledge로 확정돼 있어
쪼갤 이유가 없고, 5종 중 하나라도 빠지면 `StrategyRegistry.get()`이 런타임에 터지기 때문이다.

### 유형 전략 5종 (`memory/type/troubleshooting/`)

| 단계 | 클래스 | 핵심 |
|------|--------|------|
| S2 추출 | `TroubleshootingExtraction` | 마스킹 원문 → `TroubleshootingCard`. 파싱 실패 시 원문 보존 fallback |
| — | `TroubleshootingCard` | 스키마 단일 기준점(record). `Attempt` 중첩 record. 정규화 담당 |
| R 검색표현 | `TroubleshootingSearchRepresentation` | PRD 이중 벡터 — `problem`·`solution` kind |
| P 플래너 | `TroubleshootingPlanContribution` | BM25 2.0 / vector 1.2 (지식과 정반대) |
| S4 판정 | `TroubleshootingJudge` | error_signature·root_cause·environment 대조 |
| A 답변 | `TroubleshootingAnswer` | 증상·에러·환경·시도·원인·해결·상태 근거 조각 |

스키마는 PRD §04를 그대로 따른다(`symptom`·`error_message`·`error_signature`·`environment`·
`attempts[]`·`root_cause`·`final_solution`·`status`) + 공유 코드가 읽는 `title`·`summary`·`keywords`.
JSON 키는 **PRD 표기(snake_case)** 를 유지해 프롬프트·검색 표현·프론트가 같은 이름을 본다.

프롬프트 3개를 리소스로 추가: `troubleshooting-extraction.md` · `troubleshooting-judgement.md` ·
`type-classification.md`.

### 저장 경로 유형 라우팅 (`store/TypeClassifier`)

- 후보는 **등록된 `ExtractionStrategy`의 유형**(자가 등록). 추출 전략 없는 유형으로 라우팅하면 바로
  터지므로 후보를 그 집합으로 한정하고, 덕분에 새 유형이 등록되면 라우팅이 자동으로 켜진다.
- 유형이 1개면 **LLM을 부르지 않는다**(비용·지연). 2개 이상이면 LLM 1콜.
- 격하: 호출 실패·모르는 출력·미지원 유형 → 기본 유형(KNOWLEDGE). 전부 warn 로그로 드러낸다.

### 공유 코드에서 knowledge 하드코딩 제거 (OCP)

유형을 하나 더 붙이자 공유 코드 3곳이 knowledge 모양에 묶여 있는 것이 드러났다. 유형이 늘 때마다
공유 코드를 고쳐야 하는 구조라 전략에 위임했다.

| 위치 | 전 | 후 |
|------|----|----|
| `QueryPipeline.buildEvidencePrompt` | 답변 프롬프트가 `title/summary/facts`를 직접 읽음 | `AnswerContribution.render()`에 위임(번호·질문만 공유 코드) |
| `SimilarMemoryFinder.representativeText` | `"document"` kind 가정 | `document` → 없으면 **첫 kind**(TS는 `problem`) |
| `MemoryDetailResponse` | knowledge 필드만 평면화 | `structured` 맵으로 카드 전체 전달 |

`KnowledgeAnswer.render()`는 요약만 내는 stub이었는데, 위임을 받으면서 제목·요약·사실을 내도록 채웠다
(공유 프롬프트가 담던 것과 동일 — `KnowledgeAnswerTest`로 회귀 고정).

LLM 응답에서 JSON 구간을 뽑는 코드가 4곳으로 복제될 상황이라 `common/LlmJson`으로 뽑고 knowledge
2곳도 그걸 쓰게 했다.

## 설계 판단

- **`status` 기본값 = `UNRESOLVED`** — 모델이 모르는 값을 주었을 때 "해결됐다"고 단정하는 쪽이 그
  반대보다 위험하다(근거 없는 생성 금지의 연장). `outcome`도 모르는 값을 `failed`로 위장하지 않고
  `unknown`으로 둔다.
- **`attempts`는 `{action, result, outcome}` 객체 배열** — PRD는 `attempts[]`까지만 정하고 항목
  모양을 정하지 않았다. "뭘 시도했었지"를 회상하려면 조치·결과가 분리돼야 하고, 실패 시도 보존
  (🟠 중대 실패)을 기계적으로 채점하려면 `outcome` 라벨이 필요하다.
- **임베딩 kind는 2개(problem·solution)** — PRD §04의 이중 벡터를 그대로. attempts를 세 번째 kind로
  두는 안은 임베딩 비용 1.5배 + PRD 이탈이라 기각했다. attempts는 keywords·BM25와 리랭크(RR),
  그리고 답변 근거로 커버한다.
- **error_signature를 `keywords`에 병합** — 에러 코드·예외명은 정확 토큰 매칭이 벡터보다 강하다
  (PRD §04). BM25 색인(`search_tsv`)이 읽는 필드는 `title·summary·keywords`뿐이라, 시그니처를
  keywords에 넣어 공유 인덱싱 코드를 고치지 않고 정확 매칭 대상에 올린다.
- **kind 순서에 의미를 둔다** — `LinkedHashMap`으로 `problem`을 먼저 낸다. S4 유사 판정이 첫 kind를
  대표 텍스트로 쓰므로(위 표), 트러블슈팅은 "같은 문제인가"를 증상·시그니처로 먼저 본다.
- **채널 가중치는 PRD §2.2 예시값(bm25 2.0 / vector 1.2)을 시작점으로** — 라벨셋 fit 튜닝 대상이며
  바꿀 때 커밋에 근거를 남긴다. 구현되지 않은 채널(exact·raw_bm25·raw_vector)에는 이름을 주지 않는다
  (없는 채널 가중치는 융합에서 조용히 무시돼 "설정했는데 안 먹는" 상태가 된다).
- **라우팅 입력만 앞 4,000자로 자른다** — 유형은 도입부에서 드러나고, 전문을 넣으면 긴 붙여넣기마다
  토큰이 폭발한다. 추출(S2/S3)은 원문 전체를 겹침 청킹으로 커버하므로 **내용 유실이 아니다**.
  절단 사실은 로그로 드러낸다.
- **유형 설명은 코드가 아니라 프롬프트에** — `TypeClassifier`는 `MemoryType.name()` 매칭만 하므로 유형이
  늘어도 고치지 않는다(OCP). 유형이 무엇인지 설명하는 산문은 `type-classification.md`에 있다
  (프롬프트=콘텐츠 규약, knowledge 슬라이스 1과 동일).
- **Flyway 변경 없음** — `memory.type`은 이미 유형 문자열을 받고, 임베딩은 제네릭 키 테이블
  `memory_embedding(kind, vector)`이라 새 kind는 행 추가일 뿐이다(architecture.md 가드레일 3).

## 검증

단위테스트 8개 클래스 신규 + 3개 보강, 관련 순수 단위테스트 **172개 전부 통과**.

- S2: 스키마 매핑 · **attempts 3건(실패 2건) 보존** · 시그니처→keywords · status/outcome 정규화 ·
  산문 감싼 JSON · fallback 원문 보존 · 🔴 ctx 바인딩 클라이언트만 사용
- R: problem/solution 구성 · kind 2개와 순서 · 미해결 카드는 solution kind 없음 · 전부 비면 빈 맵
- P: 5종 SPI 커버리지 · BM25 > vector · 채널 이름 고정(오타 방지)
- S4: verdict 4종 파싱 · 🔴 **CONFLICT 보존**(release-gate 태그) · 모르는 verdict/호출 실패 → SUPPLEMENT
- A: 전 필드 근거 노출 · 실패 시도 노출 · 없는 필드 라벨 생략 · 빈 카드 "(내용 없음)"
- 라우팅: LLM 지목대로 · 유형 1개면 미호출 · 미지원/실패 격하 · 프롬프트에 마스킹 원문+후보 · 절단 범위

> **환경 주의**: 이 저장소가 OneDrive 동기화 폴더 안에 있어 `build/`의 `.class`가 플레이스홀더로 바뀌면
> Gradle 스냅샷이 깨진다(`not a regular file`). 또 `./gradlew test`는 Gradle 9.5.1 + JUnit Platform
> 6.0.3 조합에서 테스트 클래스를 못 찾고, `spotless`는 google-java-format 1.25.2 + JDK 25에서
> `NoSuchMethodError`로 죽는다. 위 172개는 빌드 출력을 OneDrive 밖으로 옮기고 JUnit Launcher를 직접
> 띄워 돌린 결과다. **세 문제 모두 이 변경과 무관한 기존 환경 이슈**이며 별도 정리가 필요하다.

DB가 필요한 `@SpringBootTest`(승인→인덱싱→검색 스모크, 교차유출 게이트)는 Docker 미가동으로 이번에
돌리지 못했다.

## 범위 밖 / 후속

- **프론트 렌더** — 이 슬라이스에서는 `structured`로 필드를 내려보내는 것까지만 했다.
  화면 렌더는 슬라이스 2에서 완료 → [troubleshooting-02-frontend.md](troubleshooting-02-frontend.md).
- **RR 리랭크 프롬프트** — 지금은 title·summary만 넣는다. PRD §03은 트러블슈팅 리랭크를 "증상·근본원인·
  해결책 적용 가능성" 기준으로 요구하므로, 리랭크 후보 요약도 유형 전략에 위임할 여지가 있다.
- **다차원 분류(C)** — 지금은 domain 축 1개(유형)만. `route`·`novelty`·`ts_subtype`·`entities`·
  confidence는 미구현.
- **exact 채널·boost/penalty** — PRD 4채널·boost 표는 아직 검색기에 없다.
- **승인 시 재발(RECURRENCE) 반영** — 기존 memory hits++/상태 전이(knowledge 슬라이스 3의 후속과 동일).
- **Eval 셋** — PRD §07의 유형별 라벨셋(특히 C의 ts_subtype, S4 재발↔신규)은 여전히 없다.
