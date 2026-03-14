package com.example.popping.controller.mvc;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
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

    @MessageMapping("/like/add")
    @SendTo("/topic/like-updates")
    public LikeResponse handleAddLike(@Valid LikeRequest request,
                                   SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        return likeService.addLike(request, userPrincipal);
    }

    @MessageMapping("/like/remove")
    @SendTo("/topic/like-updates")
    public LikeResponse handleRemoveLike(@Valid LikeRequest request,
                                         SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        return likeService.removeLike(request, userPrincipal);
    }

    @PostMapping("/api/test/likes/add")
    @ResponseBody
    public LikeResponse add(@RequestBody LikeRequest request,
                            @AuthenticationPrincipal UserPrincipal principal) {
        return likeService.addLike(request, principal);
    }

    @PostMapping("/api/test/likes/remove")
    @ResponseBody
    public LikeResponse remove(@RequestBody LikeRequest request,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return likeService.removeLike(request, principal);
    }

    private UserPrincipal extractUserPrincipal(Principal principal) {
        if (!(principal instanceof Authentication auth)) return null;

        Object p = auth.getPrincipal();
        return (p instanceof UserPrincipal up) ? up : null;
    }
}
