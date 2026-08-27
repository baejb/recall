package com.recall.auth.service.entity;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 {@link AppUser} — <b>id 가 부여된</b> 인스턴스를 만든다.
 *
 * <p>{@code id} 는 DB identity 가 채우므로 프로덕션에는 세터가 없다(있으면 애플리케이션이 id 를 정할 수 있게 된다). 테스트만 필요한 값을 위해
 * 프로덕션 가시성을 넓히지 않는다는 규칙(java-spring.md §1)에 따라, 같은 패키지의 테스트 픽스처가 리플렉션으로 채운다.
 */
public final class AppUserFixture {

    private AppUserFixture() {}

    /** 영속된 것처럼 id 가 있는 사용자. */
    public static AppUser persisted(
            long id, String provider, String subject, String email, String displayName) {
        AppUser user = new AppUser(provider, subject, email, displayName);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
