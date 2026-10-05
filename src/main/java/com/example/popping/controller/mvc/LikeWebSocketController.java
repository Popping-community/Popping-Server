package com.example.popping.controller.mvc;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.filter.GuestIdentifierFilter;
import com.example.popping.relay.LikeBroadcaster;
import com.example.popping.service.LikeService;

/**
 * Applies likes sent over STOMP and tells every viewer of the target.
 *
 * <p>The handlers return nothing on purpose: a return value would also be sent to Spring's
 * default destination ({@code /topic/like/add}). The update is sent explicitly once the
 * service has returned, which is after its transaction committed, and a failing service
 * sends nothing.
 */
@Controller
@RequiredArgsConstructor
public class LikeWebSocketController {

    private final LikeService likeService;
    private final LikeBroadcaster likeBroadcaster;

    @MessageMapping("/like/add")
    public void handleAddLike(@Valid LikeRequest request,
                              SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        likeBroadcaster.broadcast(likeService.addLike(request, userPrincipal, guestUuid(headerAccessor)));
    }

    @MessageMapping("/like/remove")
    public void handleRemoveLike(@Valid LikeRequest request,
                                 SimpMessageHeaderAccessor headerAccessor) {

        UserPrincipal userPrincipal = extractUserPrincipal(headerAccessor.getUser());
        likeBroadcaster.broadcast(likeService.removeLike(request, userPrincipal, guestUuid(headerAccessor)));
    }

    // Put there by GuestIdentityHandshakeInterceptor from the verified cookie.
    private String guestUuid(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        if (attributes == null) return null;
        return attributes.get(GuestIdentifierFilter.GUEST_UUID_ATTR) instanceof String uuid ? uuid : null;
    }

    private UserPrincipal extractUserPrincipal(Principal principal) {
        if (!(principal instanceof Authentication auth)) return null;

        Object p = auth.getPrincipal();
        return (p instanceof UserPrincipal up) ? up : null;
    }
}
