package com.example.popping.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.popping.domain.User;
import com.example.popping.dto.JoinRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // Mockito 환경 설정
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("회원가입 성공 - 아이디/닉네임 중복이 없고 저장이 성공해야 한다")
    void join_Success() {
        // given
        JoinRequest req = new JoinRequest("testId", "testPw", "testNick");
        when(userRepository.existsByLoginId(anyString())).thenReturn(false);
        when(userRepository.existsByNickname(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");

        // when
        userService.join(req);

        // then
        verify(userRepository, times(1)).save(any()); // save가 1번 호출되었는지 검증
        verify(passwordEncoder, times(1)).encode("testPw");
    }

    @Test
    @DisplayName("회원가입 실패 - 아이디 중복 시 CustomAppException 발생")
    void join_Fail_DuplicateLoginId() {
        // given
        JoinRequest req = new JoinRequest("duplicateId", "pw", "nick");
        when(userRepository.existsByLoginId("duplicateId")).thenReturn(true);

        // when & then
        CustomAppException exception = assertThrows(CustomAppException.class, () -> {
            userService.join(req);
        });

        assertEquals(ErrorType.DUPLICATE_LOGIN_ID, exception.getErrorType());
        verify(userRepository, never()).save(any()); // 중복 발생 시 저장은 호출되면 안 됨
    }

    @Test
    @DisplayName("사용자 조회 - ID가 존재하면 사용자 객체를 반환해야 한다")
    void getLoginUserById_Success() {
        // given
        Long userId = 1L;
        User user = User.builder().id(userId).loginId("test").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // when
        User result = userService.getLoginUserById(userId);

        // then
        assertNotNull(result);
        assertEquals(userId, result.getId());
    }

    @Test
    @DisplayName("ID-닉네임 맵 변환 - List<Object[]>를 Map으로 잘 변환해야 한다")
    void getUserIdToNicknameMap_Success() {
        // given
        Set<Long> ids = Set.of(1L, 2L);
        Object[] row1 = {1L, "nick1"};
        Object[] row2 = {2L, "nick2"};
        when(userRepository.findUserIdAndNicknameByIds(ids)).thenReturn(List.of(row1, row2));

        // when
        Map<Long, String> resultMap = userService.getUserIdToNicknameMap(ids);

        // then
        assertEquals(2, resultMap.size());
        assertEquals("nick1", resultMap.get(1L));
        assertEquals("nick2", resultMap.get(2L));
    }
}
