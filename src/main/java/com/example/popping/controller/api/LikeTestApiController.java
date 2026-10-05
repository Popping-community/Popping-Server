package com.example.popping.controller.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.filter.GuestIdentifierFilter;
import com.example.popping.relay.LikeBroadcaster;
import com.example.popping.service.LikeService;

/**
 * Likes over plain HTTP, for experiments and load tests that cannot speak STOMP. Off unless
 * {@code app.test-api.likes.enabled=true}: users like through STOMP, and an open endpoint
 * that needs no login is one more way in for scripts.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.test-api.likes.enabled", havingValue = "true")
public class LikeTestApiController {

    private final LikeService likeService;
    private final LikeBroadcaster likeBroadcaster;

    @PostMapping("/api/test/likes/add")
    public LikeResponse add(@Valid @RequestBody LikeRequest request,
                            @AuthenticationPrincipal UserPrincipal principal,
                            @RequestAttribute(name = GuestIdentifierFilter.GUEST_UUID_ATTR, required = false)
                            String guestUuid) {
        return likeBroadcaster.broadcast(likeService.addLike(request, principal, guestUuid));
    }

    @PostMapping("/api/test/likes/remove")
    public LikeResponse remove(@Valid @RequestBody LikeRequest request,
                               @AuthenticationPrincipal UserPrincipal principal,
                               @RequestAttribute(name = GuestIdentifierFilter.GUEST_UUID_ATTR, required = false)
                               String guestUuid) {
        return likeBroadcaster.broadcast(likeService.removeLike(request, principal, guestUuid));
    }
}
