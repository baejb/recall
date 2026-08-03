package com.recall.memory.type;

import com.recall.common.TypeStrategy;
import java.util.Map;

/**
 * 유형별 검색 표현 — "무엇을 임베딩하나"를 정한다(지식=fact/document, 트러블슈팅=problem/solution 이중 벡터 + error_signature).
 * "무엇을 임베딩하나"가 연관성의 절반을 결정한다(PRD).
 *
 * <p>임베딩은 제네릭 키 테이블 {@code memory_embedding(kind, vector)} 에 kind별로 저장한다 (유형별 벡터 컬럼을 memory에 박지 않는다
 * — architecture.md 가드레일 3).
 */
public interface SearchRepresentation extends TypeStrategy {

    /** 구조화 필드 → 임베딩 대상 텍스트(kind → text). 예: 트러블슈팅 {@code {"problem": ..., "solution": ...}}. */
    Map<String, String> embeddingTexts(Map<String, Object> structured);
}
