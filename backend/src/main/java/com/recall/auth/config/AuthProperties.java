package com.recall.auth.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정({@code recall.auth.*}).
 *
 * <p><b>허용목록이 왜 필요한가</b> — Google 로그인은 <b>누구나</b> 시작할 수 있다. 셀프호스트 인스턴스를 인터넷에 올려 두면 아무 구글 계정이나 로그인해
 * 자기 기억 공간을 만들 수 있고, 그건 이 제품이 의도한 것이 아니다. 그래서 <b>로그인 자체를 이메일로 제한</b>한다(fail-closed: 목록에 없으면 403,
 * 계정도 만들지 않는다).
 *
 * <p>이메일을 정규화해 비교한다(trim + 소문자, {@link Locale#ROOT}) — 터키어 로케일에서 {@code i→İ} 로 올라가 매칭이 전부 실패하는 것을
 * 막는다(이 저장소가 이미 겪은 종류의 버그다).
 *
 * @param allowedEmails 로그인 허용 이메일. 환경변수는 {@code RECALL_ALLOWED_EMAILS}(쉼표 구분)
 */
@ConfigurationProperties("recall.auth")
public record AuthProperties(List<String> allowedEmails) {

    public AuthProperties {
        allowedEmails = allowedEmails == null ? List.of() : List.copyOf(allowedEmails);
    }

    /** 정규화된 허용 이메일 집합. */
    public Set<String> normalizedAllowedEmails() {
        return allowedEmails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(AuthProperties::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 이 이메일이 로그인 허용 대상인가. */
    public boolean allows(String email) {
        return email != null && normalizedAllowedEmails().contains(normalize(email));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
