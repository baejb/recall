package com.recall.common.config;

/**
 * 현재 요청의 사용자 id 를 해석하는 seam(멀티유저 격리의 경계).
 *
 * <p>동기 요청 경로(조회·목록)는 이 provider 로 "누구의 데이터인가"를 정한다. 쓰기/비동기 경로는 SecurityContext 가 스레드에 없을 수 있어 이
 * provider 에 의존하지 않고 소유 capture 의 user_id 에서 파생한다.
 *
 * <p>지금은 OAuth 인증이 배선되기 전이라 {@link BootstrapCurrentUserProvider} 가 항상 부트스트랩 사용자(1)를 반환한다. 후속(팀원)이 이
 * 인터페이스의 구현을 SecurityContext 의 OAuth principal 로 app_user 를 조회/생성해 반환하도록 교체하면, 나머지 스코핑 코드는 그대로
 * 멀티유저로 동작한다.
 */
public interface CurrentUserProvider {

    /** 현재 요청 주체의 app_user.id. */
    long currentUserId();
}
