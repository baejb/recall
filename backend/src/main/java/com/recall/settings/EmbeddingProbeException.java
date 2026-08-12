package com.recall.settings;

/** 임베딩 설정 저장 전 프로브(test-before-save) 실패 시 던진다. 키 값은 메시지에 담지 않는다. */
public class EmbeddingProbeException extends RuntimeException {

    public EmbeddingProbeException(String message) {
        super(message);
    }
}
