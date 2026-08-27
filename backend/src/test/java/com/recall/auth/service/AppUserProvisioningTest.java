package com.recall.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recall.auth.config.AuthProperties;
import com.recall.auth.repository.AppUserRepository;
import com.recall.auth.service.entity.AppUser;
import com.recall.auth.service.entity.AppUserFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 🔴 계정이 만들어지는 <b>유일한 경로</b>가 허용목록을 지키는지 고정한다.
 *
 * <p>이 검사가 무너지면 셀프호스트 인스턴스에 아무 구글 계정이나 로그인해 자기 기억 공간을 만들 수 있다. 판정과 생성이 같은 자리에 있는 이유도 그것이다 — 떨어져 있으면
 * "판정은 했는데 생성 경로가 그걸 안 보는" 조합이 생긴다.
 */
@Tag("release-gate")
class AppUserProvisioningTest {

    private static final String PROVIDER = "google";
    private static final String SUBJECT = "sub-123";

    private final AppUserRepository users = mock(AppUserRepository.class);

    // AppUserWriter 는 리포지토리를 감싼 트랜잭션 경계뿐이라 실물을 쓴다 — 목으로 바꾸면 이 테스트가
    // 지키려는 것("계정이 만들어지는 유일한 경로가 허용목록을 지킨다")이 사라진다.
    private AppUserProvisioning provisioning(String... allowed) {
        return new AppUserProvisioning(
                new AppUserWriter(users), new AuthProperties(List.of(allowed)));
    }

    @Test
    @DisplayName("🔴 허용목록에 없는 이메일은 거절하고 계정을 만들지 않는다")
    void rejectsUnlistedEmailWithoutCreatingUser() {
        AppUserProvisioning provisioning = provisioning("owner@example.com");

        LoginNotAllowedException thrown =
                assertThrows(
                        LoginNotAllowedException.class,
                        () ->
                                provisioning.resolveOrCreate(
                                        PROVIDER, SUBJECT, "stranger@example.com", "낯선 사람"));

        assertEquals("stranger@example.com", thrown.email());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("🔴 허용목록이 비어 있으면 아무도 통과하지 못한다(fail-closed)")
    void emptyAllowlistRejectsEveryone() {
        AppUserProvisioning provisioning = provisioning();

        assertThrows(
                LoginNotAllowedException.class,
                () -> provisioning.resolveOrCreate(PROVIDER, SUBJECT, "owner@example.com", "주인"));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("허용된 이메일의 첫 로그인은 새 계정을 만든다")
    void createsUserOnFirstLogin() {
        AppUserProvisioning provisioning = provisioning("owner@example.com");
        when(users.findByProviderAndSubject(PROVIDER, SUBJECT)).thenReturn(Optional.empty());
        AppUser saved = mock(AppUser.class);
        when(saved.getId()).thenReturn(2L);
        when(users.save(any(AppUser.class))).thenReturn(saved);

        long id = provisioning.resolveOrCreate(PROVIDER, SUBJECT, "owner@example.com", "주인");

        assertEquals(2L, id);
        ArgumentCaptor<AppUser> created = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(created.capture());
        assertEquals(PROVIDER, created.getValue().getProvider());
        assertEquals(SUBJECT, created.getValue().getSubject());
    }

    @Test
    @DisplayName("같은 (provider, subject) 재로그인은 기존 계정을 쓴다 — 이메일이 바뀌어도 새로 만들지 않는다")
    void reusesExistingUserEvenWhenEmailChanged() {
        AppUserProvisioning provisioning = provisioning("new@example.com");
        AppUser existing =
                AppUserFixture.persisted(7L, PROVIDER, SUBJECT, "old@example.com", "예전 이름");
        when(users.findByProviderAndSubject(PROVIDER, SUBJECT)).thenReturn(Optional.of(existing));

        assertEquals(
                7L, provisioning.resolveOrCreate(PROVIDER, SUBJECT, "new@example.com", "새 이름"));

        verify(users, never()).save(any());
        // 식별자는 그대로, 표시 정보는 갱신 — 화면이 옛 이메일을 보여주면 누구로 로그인했는지 잘못 알려준다.
        assertEquals("new@example.com", existing.getEmail());
        assertEquals("새 이름", existing.getDisplayName());
        assertTrue(existing.getLastLoginAt() != null, "로그인 시각이 기록돼야 한다");
    }

    @Test
    @DisplayName("동시 첫 로그인 — 유니크 위반이면 먼저 만들어진 행을 쓴다(500 으로 새지 않는다)")
    void racedFirstLoginReusesTheRowCreatedByTheOtherRequest() {
        AppUserProvisioning provisioning = provisioning("owner@example.com");
        AppUser winner = AppUserFixture.persisted(5L, PROVIDER, SUBJECT, "owner@example.com", "주인");
        // 1회차: 아무도 없다고 보고 insert → 상대가 먼저 넣어 유니크 위반.
        // 2회차(재시도): 상대가 만든 행이 보인다.
        when(users.findByProviderAndSubject(PROVIDER, SUBJECT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(users.save(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("uq_app_user_provider_subject"));

        assertEquals(
                5L, provisioning.resolveOrCreate(PROVIDER, SUBJECT, "owner@example.com", "주인"));
    }

    @Test
    @DisplayName("허용목록 비교는 대소문자·공백을 무시한다")
    void allowlistComparisonIsNormalized() {
        AppUserProvisioning provisioning = provisioning("  Owner@Example.COM  ");
        when(users.findByProviderAndSubject(PROVIDER, SUBJECT)).thenReturn(Optional.empty());
        AppUser saved = mock(AppUser.class);
        when(saved.getId()).thenReturn(2L);
        when(users.save(any(AppUser.class))).thenReturn(saved);

        assertEquals(
                2L, provisioning.resolveOrCreate(PROVIDER, SUBJECT, "owner@example.com", "주인"));
    }
}
