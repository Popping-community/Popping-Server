package com.example.popping.service;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.User;
import com.example.popping.dto.JoinRequest;
import com.example.popping.dto.LoginRequest;
import com.example.popping.repository.UserRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class LoginService {

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

    public User login(LoginRequest req) {
        Optional<User> optionalUser = userRepository.findByLoginId(req.getLoginId());

        if (optionalUser.isEmpty()) {
            return null;
        }

        User user = optionalUser.get();

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            return null;
        }

        return user;
    }

    public User getLoginUserById(Long userId) {
        if (userId == null) {
            return null;
        }

        Optional<User> optionalUser = userRepository.findById(userId);
        return optionalUser.orElse(null);
    }

    public User getLoginUserByLoginId(String loginId) {
        if (loginId == null) {
            return null;
        }

        Optional<User> optionalUser = userRepository.findByLoginId(loginId);
        return optionalUser.orElse(null);
    }
}
