package com.recall.auth.service;

import org.springframework.security.core.AuthenticationException;

/**
 * 허용목록에 없는 이메일의 로그인 시도.
 *
 * <p>{@link AuthenticationException} 을 상속하는 이유: Spring Security 의 OAuth 로그인 실패 경로를 그대로 타야 실패 핸들러가
 * 잡아 로그인 화면으로 되돌릴 수 있다. 도메인 예외({@code ApiException})로 던지면 필터 안에서 터져 전역 핸들러에 닿지 못하고 500 이 된다.
 *
 * <p>사용자에게 보이는 메시지에 <b>이유를 구체적으로 쓰지 않는다</b> — "이 인스턴스에 허용된 계정이 아니다"까지만 알린다. 어떤 이메일이 허용목록에 있는지를 추측할
 * 단서를 주지 않는다.
 */
public class LoginNotAllowedException extends AuthenticationException {

    private final String email;

    public LoginNotAllowedException(String email) {
        super("허용되지 않은 계정입니다");
        this.email = email;
    }

    /** 거절된 이메일(서버 로그·감사용. 응답에는 싣지 않는다). */
    public String email() {
        return email;
    }
}
