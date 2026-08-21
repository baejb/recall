# knowledge 검색 — Voyage 임베딩 + vector·BM25 하이브리드(RRF)

> 커밋: `f1d7ad9` · 상태: 완료

## 왜

조회 경로의 검색이 stub이었다: `QueryPipeline.search()`는 `findByTypeAndStatus` 단순 DB 조회뿐,
검색 표현(R)·플래너(P) SPI의 knowledge 구현이 없고, `memory_embedding` 테이블(V2)을 다루는
Java 코드가 없었다. 질문 → 관련 지식 검색이 실제로 동작하게 만든다.

## 무엇을

- **임베딩 어댑터**: `EmbeddingClient` 포트를 `embedDocument`/`embedQuery`로 분리(Voyage
  input_type 활용). `VoyageEmbeddingClient`(RestClient) + `EmbeddingProperties`
  (`recall.llm.embedding.*`, BYO key). `LlmConfig`가 키 있으면 Voyage, 없으면 stub.
- **knowledge SPI**: `KnowledgeSearchRepresentation`(document→kind='document', facts→'fact'),
  `KnowledgePlanContribution`(vector 중심 채널 가중치).
- **검색 인프라**: `MemorySearchStore`(JdbcTemplate, 엔티티 없이 native) — 임베딩 upsert,
  search_tsv 갱신, 코사인 벡터 검색, BM25. `RrfFusion`(순수 함수), `HybridSearchService`.
- **배선**: `QueryPipeline`(하이브리드 검색), `ReviewService.approve`(승인 시 인덱싱).

## 설계 판단

- **벡터 매핑 = JdbcTemplate native 문자열 리터럴**(`CAST(? AS vector)`) — pgvector-java/
  hibernate-vector 의존성 추가 없이. memory_embedding은 JPA 엔티티로 안 만들고 native로만 다룬다.
- **RRF 융합은 결정론**(불변 원칙: 융합에 LLM 금지) — `weight/(K+rank)` 합산, K=60,
  동점은 id로 tie-break해 같은 입력=같은 출력. knowledge 채널 가중치 vector 2.0 / bm25 1.0.
- **Flyway 변경 없음** — memory_embedding·search_tsv가 이미 V2에 존재.
- 임베딩 실패는 memory를 유지한 채 로그로 드러냄(부분성공 노출).

## 검증

단위테스트(RRF 융합 결정론·SearchRepresentation 매핑) · 부팅 스모크(승인→인덱싱→질문 시
관련 지식이 근거와 함께 상위). 키 미설정이라 vector는 stub, BM25·인덱싱·native 쿼리·RRF는 실동작.

## 범위 밖 / 후속

exact 채널·리랭크(RR)·다차원 분류(C 실제화)·τ 임계값 외부화. (S4 판정 → 슬라이스 3.)

> 후속에서 BM25 질의를 OR 결합으로 고침(슬라이스 3): 한국어 형태소 사전이 없어 plainto_tsquery의
> AND 매칭이 조사 차이로 실패하던 문제.
