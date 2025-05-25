package com.example.popping.controller;

import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.dto.JoinRequest;
import com.example.popping.service.UserService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    @GetMapping("/join")
    public String addForm(@ModelAttribute JoinRequest joinRequest) {
        return "users/joinForm";
    }

    @PostMapping("/join")
    public String save(@Valid @ModelAttribute JoinRequest joinRequest, BindingResult result) {
        if (result.hasErrors()) {
            return "users/joinForm";
        }
        userService.join(joinRequest);
        return "redirect:/login";
    }
}
