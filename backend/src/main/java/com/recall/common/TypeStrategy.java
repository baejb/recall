package com.recall.common;

/**
 * 유형별 전략(SPI)의 공통 계약. 각 전략은 자신이 담당하는 {@link MemoryType}을 스스로 밝힌다(자가 등록). 덕분에 유형 추가 시 중앙 등록 리스트를 편집할
 * 필요가 없다(architecture.md 가드레일 5).
 */
public interface TypeStrategy {

    /** 이 전략이 담당하는 메모리 유형. */
    MemoryType supports();
}
