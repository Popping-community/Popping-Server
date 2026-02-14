package com.example.popping.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("로그인 ID로 사용자를 찾으면 UserPrincipal을 반환해야 한다")
    void loadUserByUsername_Success() {
        // given
        String loginId = "testUser";
        User user = User.builder()
                .loginId(loginId)
                .passwordHash("hashedPassword")
                .nickname("tester")
                .build();

        when(userRepository.findByLoginId(loginId)).thenReturn(Optional.of(user));

        // when
        UserDetails result = userDetailsService.loadUserByUsername(loginId);

        // then
        assertNotNull(result);
        assertEquals(loginId, result.getUsername());
        assertTrue(result instanceof UserPrincipal); // 반환 타입이 UserPrincipal인지 확인
        verify(userRepository, times(1)).findByLoginId(loginId);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 UsernameNotFoundException이 발생해야 한다")
    void loadUserByUsername_Fail() {
        // given
        String loginId = "wrongId";
        when(userRepository.findByLoginId(loginId)).thenReturn(Optional.empty());

        // when & then
        assertThrows(UsernameNotFoundException.class, () -> {
            userDetailsService.loadUserByUsername(loginId);
        });

        verify(userRepository, times(1)).findByLoginId(loginId);
    }
}
