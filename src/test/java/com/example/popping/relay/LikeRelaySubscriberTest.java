package com.example.popping.relay;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.example.popping.domain.Like;
import com.example.popping.dto.LikeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LikeRelaySubscriberTest {

	private static final String DESTINATION = "/topic/like-updates";
	private static final byte[] CHANNEL = "test-like-channel".getBytes(StandardCharsets.UTF_8);
	private static final LikeResponse UPDATE =
			new LikeResponse(10L, Like.TargetType.COMMENT, LikeResponse.LikeAction.DISLIKED, 3, 2, 11L);

	private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final LikeRelaySubscriber subscriber =
			new LikeRelaySubscriber(messagingTemplate, objectMapper, "me", DESTINATION, meters);

	@Test
	@DisplayName("다른 인스턴스의 갱신을 이 인스턴스 브로커로 그대로 보낸다")
	void otherOrigin_deliveredToLocalBroker() throws Exception {
		receive(objectMapper.writeValueAsString(new LikeRelayMessage("other", UPDATE)));

		verify(messagingTemplate).convertAndSend(DESTINATION, UPDATE);
		assertThat(count("delivered")).isEqualTo(1);
	}

	@Test
	@DisplayName("자기가 보낸 갱신은 이미 로컬에 보냈으므로 무시한다")
	void ownOrigin_ignored() throws Exception {
		receive(objectMapper.writeValueAsString(new LikeRelayMessage("me", UPDATE)));

		verifyNoInteractions(messagingTemplate);
		assertThat(count("own")).isEqualTo(1);
	}

	@Test
	@DisplayName("깨진 본문, JSON null, 갱신 없음, 대상 없음은 예외 없이 invalid로 센다")
	void invalidBodies_counted() {
		receive("{not json");
		receive("null");
		receive("{\"origin\":\"other\"}");
		receive("{\"origin\":\"other\",\"update\":{\"targetType\":\"POST\",\"likeCount\":1}}");
		receive("{\"origin\":\"other\",\"update\":{\"targetId\":1,\"targetType\":\"NOPE\"}}");

		verifyNoInteractions(messagingTemplate);
		assertThat(count("invalid")).isEqualTo(5);
	}

	@Test
	@DisplayName("로컬 전송 실패는 리스너 밖으로 던지지 않는다")
	void localDeliveryFailure_isolated() throws Exception {
		doThrow(new MessagingException("broker down")).when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

		assertDoesNotThrow(() -> receive(objectMapper.writeValueAsString(new LikeRelayMessage("other", UPDATE))));

		assertThat(count("failed")).isEqualTo(1);
	}

	private void receive(String body) {
		subscriber.onMessage(new DefaultMessage(CHANNEL, body.getBytes(StandardCharsets.UTF_8)), null);
	}

	private double count(String result) {
		var counter = meters.find(LikeRelaySubscriber.METRIC).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}
}
