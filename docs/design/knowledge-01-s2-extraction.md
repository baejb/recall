# knowledge S2 구조화 추출 (+ 프롬프트 리소스 분리)

> 커밋: `df2b216`(추출) · `594d4a4`(프롬프트 분리) · 상태: 완료

## 왜

walking skeleton까지는 `KnowledgeExtraction`이 `{title:"[stub] 지식 카드", …}` 고정 placeholder를
반환했다. 이걸 PRD의 knowledge 스키마(topic/title·summary·keywords·facts·document)를 LLM으로
뽑는 실제 추출로 바꾼다. (이 슬라이스에선 LLM은 stub 유지 — 파싱·매핑·fallback만 실제.)

## 무엇을

- **`KnowledgeCard`** (record) — knowledge 스키마의 단일 기준점. null 리스트는 빈 리스트로 정규화.
- **`KnowledgeExtraction`** — `LlmClient`로 마스킹 원문 → 프롬프트 → 응답 JSON 파싱 →
  `KnowledgeCard` → `Map` 변환. 산문/마크다운에 감싸인 JSON도 `{`~`}`만 추출. 파싱 실패·미연동 시
  원문 보존 fallback 카드 + warn 로그.
- **프롬프트 분리**: `SYSTEM_PROMPT` 하드코딩 → `PromptLoader`(classpath 리소스 캐시) +
  `resources/prompts/knowledge-extraction.md`.

## 설계 판단

- 공유 SPI(`ExtractionStrategy`)는 `Map<String,Object>` 시그니처 유지 — typed record는 knowledge
  내부에서만. troubleshooting·StorePipeline과 공유하는 계약이라 병렬 작업 충돌을 피한다.
- 프롬프트는 코드가 아니라 콘텐츠 → 리소스 파일. 튜닝이 잦고(프롬프트 변경 시 Eval 재실행)
  재컴파일 없이 수정·버전비교하기 위해. `recall.llm.*`엔 모델 ID 등 설정만.
- `@ConditionalOnMissingBean`은 `@Component`가 아니라 `@Configuration @Bean`에 둔다(자기참조로
  제외되는 함정) → `LlmConfig`.

## 검증

단위테스트(정상 JSON·산문 감싼 JSON·fallback·리스트 정규화·제목 파생) · 부팅 스모크
(capture → `proposed`가 5필드 구조 → 승인 → memory).

## 범위 밖 / 후속

실제 LLM 어댑터 연동, SearchRepresentation·PlanContribution(→ 슬라이스 2), S4 판정(→ 슬라이스 3).
