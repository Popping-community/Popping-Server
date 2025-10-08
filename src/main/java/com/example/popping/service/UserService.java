package com.example.popping.service;

import java.util.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.User;
import com.example.popping.dto.JoinRequest;
import com.example.popping.dto.LoginRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.UserRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public boolean checkLoginIdDuplicate(String loginId) {
        return userRepository.existsByLoginId(loginId);
    }

    public boolean checkNicknameDuplicate(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    public void join(JoinRequest req) {
        if (checkLoginIdDuplicate(req.getLoginId())) {
            throw new CustomAppException(ErrorType.DUPLICATE_LOGIN_ID, "이미 사용 중인 아이디입니다: " + req.getLoginId());
        }

        if (checkNicknameDuplicate(req.getNickname())) {
            throw new CustomAppException(ErrorType.DUPLICATE_NICKNAME, "이미 사용 중인 닉네임입니다: " + req.getNickname());
        }

        userRepository.save(req.toEntity(passwordEncoder.encode(req.getPassword())));
    }

    public User getLoginUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomAppException(ErrorType.USER_NOT_FOUND,
                        "사용자를 찾을 수 없습니다: " + userId));
    }

    public Map<Long, String> getUserIdToNicknameMap(Set<Long> userIds) {
        List<Object[]> results = userRepository.findUserIdAndNicknameByIds(userIds);

        Map<Long, String> userIdToNickname = new HashMap<>();
        for (Object[] row : results) {
            Long id = (Long) row[0];
            String nickname = (String) row[1];
            userIdToNickname.put(id, nickname);
        }
        return userIdToNickname;
    }
}
