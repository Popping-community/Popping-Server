package com.example.popping.controller;


import com.example.popping.constant.SessionConst;
import com.example.popping.domain.User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.ui.Model;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(
            @SessionAttribute(name = SessionConst.LOGIN_USER, required = false)
            User loginUser,
            Model model) {
        if (loginUser == null) {
            return "home";
        }

        model.addAttribute("user", loginUser);
        return "loginHome";
    }
}
