package com.recall.settings;

import com.recall.common.BadRequestException;

/** 임베딩 설정 저장 전 프로브(test-before-save) 실패 시 던진다. 키 값은 메시지에 담지 않는다. */
public class EmbeddingProbeException extends BadRequestException {

    public EmbeddingProbeException(String message) {
        super(message);
    }
}
