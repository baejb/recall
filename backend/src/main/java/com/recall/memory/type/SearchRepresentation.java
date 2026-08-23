package com.recall.memory.type;

import com.recall.common.type.TypeStrategy;
import java.util.Map;

/**
 * 유형별 검색 표현 — "무엇을 임베딩하나"를 정한다(지식=fact/document, 트러블슈팅=problem/solution 이중 벡터 + error_signature).
 * "무엇을 임베딩하나"가 연관성의 절반을 결정한다(PRD).
 *
 * <p>임베딩은 제네릭 키 테이블 {@code memory_embedding(kind, vector)} 에 kind별로 저장한다 (유형별 벡터 컬럼을 memory에 박지 않는다
 * — architecture.md 가드레일 3).
 */
public interface SearchRepresentation extends TypeStrategy {

    /**
     * 카드 → 임베딩 대상 텍스트(kind → text). 예: 트러블슈팅 {@code {problem: ..., solution: ...}}.
     *
     * <p>반환 맵의 <b>키 순서에 의미가 있다</b> — S4 유사 판정이 {@code document}가 없는 유형에서 첫 kind 를 대표 텍스트로 쓴다. 따라서
     * 구현은 {@code LinkedHashMap} 처럼 순서가 보존되는 맵을 돌려준다. kind 이름은 {@link EmbeddingKind} 의 상수를 쓴다(리터럴
     * 금지).
     */
    Map<String, String> embeddingTexts(MemoryCard card);
}
