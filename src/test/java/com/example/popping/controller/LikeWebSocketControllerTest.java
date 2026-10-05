package com.example.popping.controller;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import com.example.popping.controller.mvc.LikeWebSocketController;
import com.example.popping.domain.Like;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.filter.GuestIdentifierFilter;
import com.example.popping.relay.LikeBroadcaster;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeWebSocketControllerTest {

	private static final LikeRequest REQUEST = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE);
	private static final LikeResponse UPDATE =
			new LikeResponse(10L, Like.TargetType.POST, LikeResponse.LikeAction.LIKED, 3, 0, 5L);

	@Mock LikeService likeService;
	@Mock LikeBroadcaster likeBroadcaster;

	@InjectMocks LikeWebSocketController controller;

	@Test
	@DisplayName("STOMP 좋아요: 서비스 결과를 모든 시청자에게 보낸다")
	void stompLike_broadcastsResult() {
		when(likeService.addLike(eq(REQUEST), any(), any())).thenReturn(UPDATE);

		controller.handleAddLike(REQUEST, SimpMessageHeaderAccessor.create());

		verify(likeBroadcaster).broadcast(UPDATE);
	}

	@Test
	@DisplayName("서비스가 실패하면 아무것도 보내지 않는다")
	void serviceFailure_sendsNothing() {
		when(likeService.removeLike(eq(REQUEST), any(), any())).thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));

		assertThatThrownBy(() -> controller.handleRemoveLike(REQUEST, SimpMessageHeaderAccessor.create()))
				.isInstanceOf(CustomAppException.class);

		verifyNoInteractions(likeBroadcaster);
	}

	@Test
	@DisplayName("STOMP 게스트 신원은 핸드셰이크가 세션에 넣은 검증된 값만 쓴다")
	void stompLike_usesGuestFromSessionAttributes() {
		SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
		accessor.setSessionAttributes(new HashMap<>(Map.of(GuestIdentifierFilter.GUEST_UUID_ATTR, "verified-uuid")));
		when(likeService.addLike(REQUEST, null, "verified-uuid")).thenReturn(UPDATE);

		controller.handleAddLike(REQUEST, accessor);

		verify(likeService).addLike(REQUEST, null, "verified-uuid");
	}

	@Test
	@DisplayName("세션에 게스트 신원이 없으면 null로 넘겨 서비스가 거절하게 한다")
	void stompLike_withoutGuestAttribute_passesNull() {
		when(likeService.addLike(REQUEST, null, null)).thenThrow(new CustomAppException(ErrorType.ACCESS_DENIED));

		assertThatThrownBy(() -> controller.handleAddLike(REQUEST, SimpMessageHeaderAccessor.create()))
				.isInstanceOf(CustomAppException.class);
		verifyNoInteractions(likeBroadcaster);
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
