package com.example.popping.config.websocket;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import com.example.popping.filter.GuestIdentifierFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GuestIdentityHandshakeInterceptorTest {

	private final GuestIdentityHandshakeInterceptor interceptor = new GuestIdentityHandshakeInterceptor();

	@Test
	@DisplayName("필터가 검증해 둔 게스트 UUID를 WebSocket 세션 속성으로 옮긴다")
	void copiesVerifiedGuestUuid() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
		request.setAttribute(GuestIdentifierFilter.GUEST_UUID_ATTR, "verified-uuid");
		Map<String, Object> attributes = new HashMap<>();

		boolean proceed = interceptor.beforeHandshake(new ServletServerHttpRequest(request),
				new ServletServerHttpResponse(new MockHttpServletResponse()),
				mock(WebSocketHandler.class), attributes);

		assertThat(proceed).isTrue();
		assertThat(attributes).containsEntry(GuestIdentifierFilter.GUEST_UUID_ATTR, "verified-uuid");
	}

	@Test
	@DisplayName("쿠키나 파라미터에 값이 있어도 필터가 검증한 값이 없으면 아무것도 넣지 않는다")
	void ignoresUnverifiedValues() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
		request.setCookies(new Cookie("guestIdentifier", "forged"));
		request.setParameter(GuestIdentifierFilter.GUEST_UUID_ATTR, "forged");
		Map<String, Object> attributes = new HashMap<>();

		interceptor.beforeHandshake(new ServletServerHttpRequest(request),
				new ServletServerHttpResponse(new MockHttpServletResponse()),
				mock(WebSocketHandler.class), attributes);

		assertThat(attributes).doesNotContainKey(GuestIdentifierFilter.GUEST_UUID_ATTR);
	}
}
