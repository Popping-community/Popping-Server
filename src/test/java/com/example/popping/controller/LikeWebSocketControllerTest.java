package com.example.popping.controller;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.example.popping.controller.mvc.LikeWebSocketController;
import com.example.popping.domain.Like;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.relay.LikeRelay;
import com.example.popping.relay.LikeRelayConfig;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeWebSocketControllerTest {

	private static final LikeRequest REQUEST = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE, "guest-1");
	private static final LikeResponse UPDATE =
			new LikeResponse(10L, Like.TargetType.POST, LikeResponse.LikeAction.LIKED, 3, 0, 5L);

	@Mock LikeService likeService;
	@Mock SimpMessagingTemplate messagingTemplate;
	@Mock LikeRelay likeRelay;

	@InjectMocks LikeWebSocketController controller;

	@Test
	@DisplayName("STOMP 좋아요: 이 서버 시청자에게 먼저 보내고, 그다음 다른 서버로 중계한다")
	void stompLike_sendsLocallyThenRelays() {
		when(likeService.addLike(eq(REQUEST), any())).thenReturn(UPDATE);

		controller.handleAddLike(REQUEST, SimpMessageHeaderAccessor.create());

		InOrder order = inOrder(messagingTemplate, likeRelay);
		order.verify(messagingTemplate).convertAndSend(LikeRelayConfig.LIKE_DESTINATION, UPDATE);
		order.verify(likeRelay).publish(UPDATE);
	}

	@Test
	@DisplayName("서비스가 실패하면 아무것도 보내지 않는다")
	void serviceFailure_sendsNothing() {
		when(likeService.removeLike(eq(REQUEST), any())).thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));

		assertThatThrownBy(() -> controller.handleRemoveLike(REQUEST, SimpMessageHeaderAccessor.create()))
				.isInstanceOf(CustomAppException.class);

		verifyNoInteractions(messagingTemplate, likeRelay);
	}

	@Test
	@DisplayName("로컬 전송이 실패해도 중계는 한다, 중계가 실패해도 요청은 성공한다")
	void failuresAreIsolated() {
		when(likeService.addLike(eq(REQUEST), any())).thenReturn(UPDATE);
		doThrow(new MessagingException("broker")).when(messagingTemplate).convertAndSend(anyString(), any(Object.class));
		doThrow(new IllegalStateException("relay")).when(likeRelay).publish(any());

		assertDoesNotThrow(() -> controller.handleAddLike(REQUEST, SimpMessageHeaderAccessor.create()));

		verify(likeRelay).publish(UPDATE);
	}

	@Test
	@DisplayName("HTTP 좋아요도 같은 방식으로 보내고 응답을 돌려준다")
	void httpLike_alsoBroadcasts() {
		when(likeService.addLike(eq(REQUEST), any())).thenReturn(UPDATE);

		LikeResponse response = controller.add(REQUEST, null);

		assertThat(response).isEqualTo(UPDATE);
		verify(messagingTemplate).convertAndSend(LikeRelayConfig.LIKE_DESTINATION, UPDATE);
		verify(likeRelay).publish(UPDATE);
	}

	@Test
	@DisplayName("STOMP 핸들러는 void라 Spring 기본 목적지로 반환값이 새지 않는다")
	void stompHandlers_returnNothing() {
		for (Method method : LikeWebSocketController.class.getDeclaredMethods()) {
			if (method.isAnnotationPresent(MessageMapping.class)) {
				assertThat(method.getReturnType()).as(method.getName()).isEqualTo(void.class);
			}
		}
	}
}
