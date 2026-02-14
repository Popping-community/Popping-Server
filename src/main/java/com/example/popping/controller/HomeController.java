package com.example.popping.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.popping.domain.UserPrincipal;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserPrincipal loginUser) {
        if (loginUser == null) {
            return "home";
        }
        return "loginHome";
    }
}
