package com.example.popping.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.example.popping.domain.Like;
import com.example.popping.dto.LikeResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeBroadcasterTest {

	private static final LikeResponse UPDATE =
			new LikeResponse(10L, Like.TargetType.POST, LikeResponse.LikeAction.LIKED, 3, 0, 5L);

	@Mock SimpMessagingTemplate messagingTemplate;
	@Mock LikeRelay likeRelay;

	@InjectMocks LikeBroadcaster broadcaster;

	@Test
	@DisplayName("이 서버 시청자에게 먼저 보내고, 그다음 다른 서버로 중계한다")
	void sendsLocallyThenRelays() {
		assertThat(broadcaster.broadcast(UPDATE)).isEqualTo(UPDATE);

		InOrder order = inOrder(messagingTemplate, likeRelay);
		order.verify(messagingTemplate).convertAndSend(LikeRelayConfig.LIKE_DESTINATION, UPDATE);
		order.verify(likeRelay).publish(UPDATE);
	}

	@Test
	@DisplayName("로컬 전송이 실패해도 중계는 한다, 중계가 실패해도 예외를 던지지 않는다")
	void failuresAreIsolated() {
		doThrow(new MessagingException("broker"))
				.when(messagingTemplate).convertAndSend(anyString(), any(Object.class));
		doThrow(new IllegalStateException("relay")).when(likeRelay).publish(any());

		assertDoesNotThrow(() -> broadcaster.broadcast(UPDATE));

		verify(likeRelay).publish(UPDATE);
	}
}
