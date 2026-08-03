package com.recall.capture;

/**
 * 원문이 저장(커밋)됐음을 알리는 이벤트. capture 모듈은 이 방송만 하고, 이후 처리(추출·판정)는 store 모듈이 구독해서 맡는다 → 모듈 순환 의존을 끊는다.
 */
public record CaptureCreatedEvent(Long captureId, String maskedText) {}
