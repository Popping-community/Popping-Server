package com.example.popping.service;

import java.util.Optional;

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
        userRepository.save(req.toEntity(passwordEncoder.encode(req.getPassword())));
    }

    public User getLoginUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomAppException(ErrorType.USER_NOT_FOUND,
                        "사용자를 찾을 수 없습니다: " + userId));
    }
}
