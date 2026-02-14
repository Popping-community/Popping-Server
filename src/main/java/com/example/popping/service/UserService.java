package com.example.popping.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.User;
import com.example.popping.dto.JoinRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.UserRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public boolean checkLoginIdDuplicate(String loginId) {
        return userRepository.existsByLoginId(loginId);
    }

    @Transactional(readOnly = true)
    public boolean checkNicknameDuplicate(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    public void join(JoinRequest req) {
        validateDuplicate(req);
        userRepository.save(req.toEntity(passwordEncoder.encode(req.getPassword())));
    }

    private void validateDuplicate(JoinRequest req) {
        if (checkLoginIdDuplicate(req.getLoginId())) {
            throw new CustomAppException(ErrorType.DUPLICATE_LOGIN_ID, "이미 사용 중인 아이디: " + req.getLoginId());
        }
        if (checkNicknameDuplicate(req.getNickname())) {
            throw new CustomAppException(ErrorType.DUPLICATE_NICKNAME, "이미 사용 중인 닉네임: " + req.getNickname());
        }
    }

    public User getLoginUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomAppException(ErrorType.USER_NOT_FOUND,
                        "사용자를 찾을 수 없습니다: " + userId));
    }

    public Map<Long, String> getUserIdToNicknameMap(Set<Long> userIds) {
        return userRepository.findUserIdAndNicknameByIds(userIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }
}
