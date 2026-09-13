package com.recall.auth.controller.dto;

/**
 * 현재 로그인 사용자. 화면이 "누구로 들어와 있는가"를 표시하고, 세션 유효성을 확인하는 데 쓴다.
 *
 * <p>{@code userId} 를 내려보내는 이유는 표시가 아니라 <b>디버깅</b>이다 — 사용자가 "내 기억이 안 보인다"고 할 때 어느 소유자로 스코프됐는지가 첫
 * 질문이다. 이 값은 이미 서버가 세션에서 아는 값이라 노출로 얻는 권한이 없다(요청의 userId 는 절대 신뢰하지 않는다).
 *
 * @param bootstrapMode 인증이 배선되지 않은 부트스트랩 모드인가. 화면이 "로그인 없이 단일 사용자로 동작 중"임을 알리는 데 쓴다 — 그 사실을 숨기면 배포된
 *     인스턴스가 열려 있는데도 정상처럼 보인다(조용한 실패 금지)
 */
public record MeResponse(long userId, String email, String displayName, boolean bootstrapMode) {}
