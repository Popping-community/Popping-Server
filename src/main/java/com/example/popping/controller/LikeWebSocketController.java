package com.example.popping.controller;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.service.LikeService;

@Controller
@RequiredArgsConstructor
public class LikeWebSocketController {
    private final LikeService likeService;

    @MessageMapping("/like")
    @SendTo("/topic/like-updates")
    public LikeResponse handleLike(@Valid LikeRequest request, SimpMessageHeaderAccessor headerAccessor) {
        Principal principal = headerAccessor.getUser();

        UserPrincipal userPrincipal = null;
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof UserPrincipal up) {
            userPrincipal = up;
        }

        return likeService.toggleLike(request, userPrincipal);
    }
}
