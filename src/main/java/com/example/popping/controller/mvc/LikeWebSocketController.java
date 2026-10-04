package com.example.popping.controller.mvc;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.relay.LikeRelay;
import com.example.popping.relay.LikeRelayConfig;
import com.example.popping.service.LikeService;

/**
 * Applies likes and tells every viewer of the target, on this instance and the others.
 *
 * <p>The STOMP handlers return nothing on purpose: a return value would also be sent to
 * Spring's default destination ({@code /topic/like/add}). The update is sent explicitly once
 * the service has returned, which is after its transaction committed, and a failing service
 * sends nothing.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LikeWebSocketController {

    private final LikeService likeService;
    private final SimpMessagingTemplate messagingTemplate;
    private final LikeRelay likeRelay;

    @MessageMapping("/like/add")
    public void handleAddLike(@Valid LikeRequest request,
                              SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        broadcast(likeService.addLike(request, userPrincipal));
    }

    @MessageMapping("/like/remove")
    public void handleRemoveLike(@Valid LikeRequest request,
                                 SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        broadcast(likeService.removeLike(request, userPrincipal));
    }

    // Broadcasts like the STOMP path, so likes driven over HTTP reach viewers too.
    @PostMapping("/api/test/likes/add")
    @ResponseBody
    public LikeResponse add(@RequestBody LikeRequest request,
                            @AuthenticationPrincipal UserPrincipal principal) {
        return broadcast(likeService.addLike(request, principal));
    }

    @PostMapping("/api/test/likes/remove")
    @ResponseBody
    public LikeResponse remove(@RequestBody LikeRequest request,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return broadcast(likeService.removeLike(request, principal));
    }

    /**
     * Local viewers first, so a Redis problem never delays or blocks them; then the relay for
     * the other instances. Neither failure stops the other, and the like itself already stands.
     */
    private LikeResponse broadcast(LikeResponse update) {
        try {
            messagingTemplate.convertAndSend(LikeRelayConfig.LIKE_DESTINATION, update);
        } catch (RuntimeException e) {
            log.warn("Like update not sent to local viewers target={}:{}", update.targetType(), update.targetId(), e);
        }
        try {
            likeRelay.publish(update);
        } catch (RuntimeException e) {
            log.warn("Like update not relayed target={}:{}", update.targetType(), update.targetId(), e);
        }
        return update;
    }

    private UserPrincipal extractUserPrincipal(Principal principal) {
        if (!(principal instanceof Authentication auth)) return null;

        Object p = auth.getPrincipal();
        return (p instanceof UserPrincipal up) ? up : null;
    }
}
