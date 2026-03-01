package com.example.popping.controller.mvc;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping(LoginController.BASE_PATH)
public class LoginController {

    static final String BASE_PATH = "/login";

    private static final String VIEW_LOGIN_FORM = "login/loginForm";
    private static final String ERROR_MESSAGE = "아이디 또는 비밀번호가 맞지 않습니다.";

    @GetMapping
    public String loginForm(@RequestParam(value = "error", required = false) Boolean error,
                            Model model) {

        if (Boolean.TRUE.equals(error)) {
            model.addAttribute("loginError", ERROR_MESSAGE);
        }

        return VIEW_LOGIN_FORM;
    }
}
