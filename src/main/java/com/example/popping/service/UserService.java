package com.example.popping.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.dto.JoinRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.UserIdNicknameView;
import com.example.popping.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public boolean isLoginIdDuplicated(String loginId) {
        return userRepository.existsByLoginId(loginId);
    }

    public boolean isNicknameDuplicated(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Transactional
    public void join(JoinRequest req) {
        validateNoDuplicate(req);

        String encodedPassword = passwordEncoder.encode(req.password());

        User user = User.create(
                req.loginId(),
                encodedPassword,
                req.nickname(),
                UserRole.USER
        );

        userRepository.save(user);
    }

    public User getLoginUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomAppException(ErrorType.USER_NOT_FOUND,
                        "사용자를 찾을 수 없습니다: " + userId));
    }

    public Map<Long, String> getUserIdToNicknameMap(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        return userRepository.findIdAndNicknameByIds(userIds).stream()
                .collect(Collectors.toMap(UserIdNicknameView::getId, UserIdNicknameView::getNickname));
    }

    private void validateNoDuplicate(JoinRequest req) {
        String loginId = req.loginId();
        String nickname = req.nickname();

        if (isLoginIdDuplicated(loginId)) {
            throw new CustomAppException(ErrorType.DUPLICATE_LOGIN_ID, "이미 사용 중인 아이디: " + loginId);
        }
        if (isNicknameDuplicated(nickname)) {
            throw new CustomAppException(ErrorType.DUPLICATE_NICKNAME, "이미 사용 중인 닉네임: " + nickname);
        }
    }
}
