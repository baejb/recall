package com.recall.common.type;

/**
 * 메모리 유형. 유형마다 저장 스키마·검색 표현·판정 기준이 다르며, 유형별 전략(SPI)으로 확장한다.
 *
 * <p>유형 추가 = 이 enum에 값 추가 + 해당 type 패키지에 SPI 구현(자가 등록) + Flyway 마이그레이션. 공유 코드에서 {@code
 * switch(MemoryType)} 로 분기하지 않는다(전략 레지스트리로만 — architecture.md 가드레일).
 */
public enum MemoryType {
    KNOWLEDGE,
    TROUBLESHOOTING
    // v2 PRD 예정 유형: PROJECT_CONTEXT, DECISION, COMMAND_CODE
}
