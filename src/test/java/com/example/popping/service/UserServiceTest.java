package com.example.popping.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.dto.JoinRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.UserIdNicknameView;
import com.example.popping.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks UserService userService;

    @Test
    @DisplayName("loginId 중복이면 true를 반환한다")
    void isLoginIdDuplicated_true_whenExists() {

        // given
        when(userRepository.existsByLoginId("test")).thenReturn(true);

        // when
        boolean result = userService.isLoginIdDuplicated("test");

        // then
        assertTrue(result);
    }

    @Test
    @DisplayName("nickname 중복이면 true를 반환한다")
    void isNicknameDuplicated_true_whenExists() {

        // given
        when(userRepository.existsByNickname("닉네임")).thenReturn(true);

        // when
        boolean result = userService.isNicknameDuplicated("닉네임");

        // then
        assertTrue(result);
    }

    @Test
    @DisplayName("회원가입: 중복 없으면 비밀번호 인코딩 후 저장한다")
    void join_success() {

        // given
        JoinRequest req = joinReq("login", "rawPw", "nick");

        when(userRepository.existsByLoginId("login")).thenReturn(false);
        when(userRepository.existsByNickname("nick")).thenReturn(false);
        when(passwordEncoder.encode("rawPw")).thenReturn("ENC_PW");

        // when
        userService.join(req);

        // then (save에 넘어간 User 내용까지 검증)
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User saved = captor.getValue();
        assertEquals("login", saved.getLoginId());
        assertEquals("ENC_PW", saved.getPasswordHash());
        assertEquals("nick", saved.getNickname());
        assertEquals(UserRole.USER, saved.getRole());
    }

    @Test
    @DisplayName("회원가입: loginId 중복이면 DUPLICATE_LOGIN_ID 예외를 던진다")
    void join_fail_duplicateLoginId() {

        // given
        JoinRequest req = joinReq("login", "rawPw", "nick");
        when(userRepository.existsByLoginId("login")).thenReturn(true);

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> userService.join(req));

        // then
        assertEquals(ErrorType.DUPLICATE_LOGIN_ID, ex.getErrorType());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("회원가입: nickname 중복이면 DUPLICATE_NICKNAME 예외를 던진다")
    void join_fail_duplicateNickname() {

        // given
        JoinRequest req = joinReq("login", "rawPw", "nick");
        when(userRepository.existsByLoginId("login")).thenReturn(false);
        when(userRepository.existsByNickname("nick")).thenReturn(true);

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> userService.join(req));

        // then
        assertEquals(ErrorType.DUPLICATE_NICKNAME, ex.getErrorType());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("로그인 유저 조회: 존재하면 User를 반환한다")
    void getLoginUserById_success() {

        // given
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // when
        User result = userService.getLoginUserById(1L);

        // then
        assertSame(user, result);
    }

    @Test
    @DisplayName("로그인 유저 조회: 없으면 USER_NOT_FOUND 예외를 던진다")
    void getLoginUserById_fail_notFound() {

        // given
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> userService.getLoginUserById(999L));

        // then
        assertEquals(ErrorType.USER_NOT_FOUND, ex.getErrorType());
    }

    @Test
    @DisplayName("id->nickname 맵: projection 결과를 Map으로 변환한다")
    void getUserIdToNicknameMap_success() {

        // given
        Set<Long> ids = Set.of(1L, 2L);

        UserIdNicknameView v1 = view(1L, "a");
        UserIdNicknameView v2 = view(2L, "b");

        when(userRepository.findIdAndNicknameByIds(ids)).thenReturn(List.of(v1, v2));

        // when
        Map<Long, String> map = userService.getUserIdToNicknameMap(ids);

        // then
        assertEquals(Map.of(1L, "a", 2L, "b"), map);
    }

    private JoinRequest joinReq(String loginId, String password, String nickname) {
        return new JoinRequest(loginId, password, nickname);
    }

    private UserIdNicknameView view(Long id, String nickname) {
        UserIdNicknameView v = mock(UserIdNicknameView.class);
        when(v.getId()).thenReturn(id);
        when(v.getNickname()).thenReturn(nickname);
        return v;
    }
}
