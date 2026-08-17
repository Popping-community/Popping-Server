package com.example.popping.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The principal ends up in the session, and the session now lives in Redis. Anything
 * still attached to it is written to that store on every login, so the password hash
 * has to be gone by then.
 */
class UserPrincipalTest {

    private UserPrincipal principal() {
        return UserPrincipal.builder()
                .userId(1L)
                .loginId("user86")
                .nickname("tester")
                .passwordHash("$2b$10$kOwRIUPIxcFA72eznzcqdOHfakehashvalueforatest")
                .role(UserRole.USER)
                .build();
    }

    @Test
    @DisplayName("인증 전에는 비밀번호 해시를 제공한다")
    void password_availableBeforeErasure() {
        assertThat(principal().getPassword()).startsWith("$2b$");
    }

    @Test
    @DisplayName("eraseCredentials 이후에는 비밀번호 해시가 남지 않는다")
    void password_clearedAfterErasure() {
        UserPrincipal user = principal();

        user.eraseCredentials();

        assertThat(user.getPassword()).isNull();
        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("인증 토큰을 지우면 principal의 해시까지 함께 지워진다")
    void tokenErasure_cascadesToPrincipal() {
        UserPrincipal user = principal();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                user, "raw-password", user.getAuthorities());

        // Spring Security의 ProviderManager가 인증 성공 후 호출하는 경로다.
        ((UsernamePasswordAuthenticationToken) auth).eraseCredentials();

        assertThat(auth.getCredentials()).isNull();
        assertThat(((UserPrincipal) auth.getPrincipal()).getPassword()).isNull();
    }

    @Test
    @DisplayName("해시를 지워도 인가에 필요한 정보는 그대로 남는다")
    void erasure_keepsAuthorizationData() {
        UserPrincipal user = principal();

        user.eraseCredentials();

        assertThat(user.getUserId()).isEqualTo(1L);
        assertThat(user.getUsername()).isEqualTo("user86");
        assertThat(user.getNickname()).isEqualTo("tester");
        assertThat(user.getAuthorities())
                .extracting("authority")
                .containsExactly(UserRole.USER.name());
    }
}
