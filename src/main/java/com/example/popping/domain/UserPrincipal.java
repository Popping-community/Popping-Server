package com.example.popping.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPrincipal implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String loginId;
    private String nickname;
    private String passwordHash;
    private UserRole role;

    public UserPrincipal(User user) {
        this.userId = user.getId();
        this.loginId = user.getLoginId();
        this.nickname = user.getNickname();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * Spring Security calls this once authentication succeeds, so the hash lives only
     * as long as the password check needs it. Without this it rides along in the
     * session - and the session is now in Redis, which would put every logged-in
     * user's BCrypt hash in a second store that has no reason to hold credentials.
     * Nothing reads {@link #getPassword()} after authentication.
     */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
