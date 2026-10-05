package com.example.popping.relay;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.dto.LikeResponse;

/** Tells every viewer of a like's target, on this instance and the others. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final LikeRelay likeRelay;

    /**
     * Local viewers first, so a Redis problem never delays or blocks them; then the relay for
     * the other instances. Neither failure stops the other, and the like itself already stands.
     */
    public LikeResponse broadcast(LikeResponse update) {
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
}
