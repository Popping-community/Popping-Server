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
@RequestMapping(UserController.BASE_PATH)
public class UserController {

    static final String BASE_PATH = "/users";

    private static final String PATH_JOIN = "/join";
    private static final String VIEW_JOIN_FORM = "users/joinForm";

    private final UserService userService;

    @GetMapping(PATH_JOIN)
    public String joinForm(@ModelAttribute("form") JoinRequest form) {
        return VIEW_JOIN_FORM;
    }

    @PostMapping(PATH_JOIN)
    public String join(@Valid @ModelAttribute("form") JoinRequest form,
                       BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return VIEW_JOIN_FORM;
        }

        userService.join(form);
        return "redirect:/login";
    }
}

