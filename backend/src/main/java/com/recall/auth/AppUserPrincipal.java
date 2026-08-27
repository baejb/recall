package com.recall.auth;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * 로그인 주체 — OIDC 사용자에 <b>{@code app_user.id} 를 붙인 것</b>.
 *
 * <p><b>왜 id 를 principal 에 싣나</b> — 소유자 스코핑은 요청마다 여러 번 {@code CurrentUserProvider.currentUserId()}
 * 를 부른다(목록·상세·설정·검색이 각자 부른다). 그때마다 {@code (provider, subject)} 로 DB 를 다시 조회하면 요청 하나에 같은 SELECT 가 여러
 * 번 나간다. 로그인 시점에 한 번 해석해 세션에 담아 두면 그 조회가 사라진다.
 *
 * <p>세션에 담기므로 {@link DefaultOidcUser} 의 직렬화 계약을 그대로 물려받는다(필드는 {@code long} 하나만 더한다).
 *
 * <p><b>{@code serialVersionUID} 를 명시하는 이유</b> — 없으면 컴파일러가 필드 구성으로부터 값을 유도한다. 인메모리 세션에서는 영향이 없지만,
 * JDBC 세션으로 옮긴 뒤 이 클래스에 필드를 하나 추가하면 그 값이 바뀌어 <b>살아 있는 세션이 전부 역직렬화에 실패한다</b>(배포 직후 전원 로그아웃, 또는 500).
 * 값을 고정해 두면 실제로 호환이 깨지는 변경에서만 우리가 의도적으로 올린다.
 */
public class AppUserPrincipal extends DefaultOidcUser {

    private static final long serialVersionUID = 1L;

    private final long appUserId;

    public AppUserPrincipal(
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            OidcUserInfo userInfo,
            String nameAttributeKey,
            long appUserId) {
        super(authorities, idToken, userInfo, nameAttributeKey);
        this.appUserId = appUserId;
    }

    /** 이 로그인 주체의 {@code app_user.id} — 모든 데이터 스코핑의 기준. */
    public long appUserId() {
        return appUserId;
    }

    /** 화면에 보여줄 이메일(허용목록 판정에 쓴 값과 같다). */
    public String email() {
        Map<String, Object> attributes = getAttributes();
        Object email = attributes.get("email");
        return email == null ? "" : email.toString();
    }

    /** 화면에 보여줄 이름. provider 가 주지 않으면 이메일로 대체한다. */
    public String displayName() {
        Object name = getAttributes().get("name");
        String display = name == null ? "" : name.toString();
        return display.isBlank() ? email() : display;
    }
}
