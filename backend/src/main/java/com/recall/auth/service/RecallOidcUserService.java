package com.recall.auth.service;

import com.recall.auth.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * OIDC 로그인의 <b>관문</b> — provider 가 사용자 정보를 준 직후, 세션이 만들어지기 전에 끼어든다.
 *
 * <p>여기서 두 가지를 한다: (1) 허용목록 판정 + {@code app_user} 조회/생성, (2) 그 결과 id 를 담은 {@link AppUserPrincipal}
 * 반환. <b>순서가 중요하다</b> — 거절이 세션 생성보다 앞이라, 허용목록에 없는 계정은 로그인 세션 자체를 갖지 못한다(뒤에서 인가로 막는 방식은 이미 인증된 세션을
 * 남긴다).
 *
 * <p><b>이메일 검증을 요구한다</b> — {@code email_verified} 가 참이 아니면 거절한다. 검증되지 않은 이메일을 허용목록과 비교하면, provider
 * 에서 남의 이메일을 주장하는 계정이 통과할 수 있다(허용목록 우회).
 */
@Service
public class RecallOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(RecallOidcUserService.class);

    private final AppUserProvisioning provisioning;

    public RecallOidcUserService(AppUserProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        String subject = user.getSubject();
        String email = claim(user, StandardClaimNames.EMAIL);
        String displayName = claim(user, StandardClaimNames.NAME);

        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            log.warn("검증되지 않은 이메일의 로그인 시도 — provider={} subject={}", provider, subject);
            throw new LoginNotAllowedException(email);
        }

        long appUserId = provisioning.resolveOrCreate(provider, subject, email, displayName);

        return new AppUserPrincipal(
                user.getAuthorities(),
                user.getIdToken(),
                user.getUserInfo(),
                StandardClaimNames.SUB,
                appUserId);
    }

    private static String claim(OidcUser user, String name) {
        Object value = user.getClaims().get(name);
        return value == null ? "" : value.toString();
    }
}
