package com.recall.capture;

import org.springframework.stereotype.Service;

/**
 * M0 마스킹 — 원문이 저장·외부 LLM·인덱스·로그로 나가기 <b>전에</b> 민감정보를 가린다(불변 원칙: 마스킹 우선). 결정론 단계(정규식 패턴)라 LLM을 쓰지
 * 않는다.
 *
 * <p>Phase 0: stub(그대로 통과). Phase 1에서 API키·토큰·비밀번호 등 패턴 마스킹을 채운다.
 */
@Service
public class MaskingService {

    /** 마스킹 결과: 가려진 텍스트 + 어디를 가렸는지(사용자 검토/복원용, JSON). */
    public record MaskResult(String maskedText, String maskedSpansJson) {}

    public MaskResult mask(String rawText) {
        // TODO(Phase 1): 정규식으로 민감정보 탐지·치환 + 스팬 기록.
        return new MaskResult(rawText, "[]");
    }
}
