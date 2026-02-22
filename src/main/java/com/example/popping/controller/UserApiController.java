package com.example.popping.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import com.example.popping.service.UserService;

@RestController
@RequiredArgsConstructor
@RequestMapping(UserApiController.BASE_PATH)
public class UserApiController {

    static final String BASE_PATH = "/api/users";

    private static final String PATH_CHECK_LOGIN_ID = "/check-login-id";
    private static final String PATH_CHECK_NICKNAME = "/check-nickname";

    private final UserService userService;

    @GetMapping(PATH_CHECK_LOGIN_ID)
    public boolean checkLoginId(@RequestParam String loginId) {
        return userService.isLoginIdDuplicated(loginId);
    }

    @GetMapping(PATH_CHECK_NICKNAME)
    public boolean checkNickname(@RequestParam String nickname) {
        return userService.isNicknameDuplicated(nickname);
    }
}
