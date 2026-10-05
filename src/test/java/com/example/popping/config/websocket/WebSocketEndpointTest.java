package com.example.popping.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SockJS endpoint offers WebSocket only and accepts the page's own origin only.
 * The upgrade itself cannot run in MockMvc; it is checked on the local stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebSocketEndpointTest {

	private static final String ORIGIN = "http://localhost";

	@Autowired MockMvc mvc;

	@Test
	@DisplayName("SockJS info는 같은 출처에 응답하고 웹소켓을 쓸 수 있다고 알린다")
	void infoAnswersSameOrigin() throws Exception {
		mvc.perform(get("/ws/info").header("Origin", ORIGIN))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"websocket\":true")));
	}

	@Test
	@DisplayName("다른 출처의 SockJS 요청은 거절한다")
	void otherOriginIsRejected() throws Exception {
		mvc.perform(get("/ws/info").header("Origin", "http://evil.example"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("다른 출처의 웹소켓 핸드셰이크도 거절한다 (SockJS 경로와 순수 경로)")
	void otherOriginHandshakeIsRejected() throws Exception {
		for (String path : new String[] {"/ws/123/session1/websocket", "/ws/websocket"}) {
			mvc.perform(get(path).header("Origin", "http://evil.example")
							.header("Upgrade", "websocket").header("Connection", "Upgrade"))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	@DisplayName("웹소켓 말고 다른 전송(XHR·eventsource·htmlfile)은 없다")
	void otherTransportsAreGone() throws Exception {
		for (String transport : new String[] {"xhr", "xhr_send", "xhr_streaming"}) {
			mvc.perform(post("/ws/123/session1/" + transport).header("Origin", ORIGIN))
					.andExpect(status().isNotFound());
		}
		for (String transport : new String[] {"eventsource", "htmlfile"}) {
			mvc.perform(get("/ws/123/session1/" + transport).param("c", "cb").header("Origin", ORIGIN))
					.andExpect(status().isNotFound());
		}
	}
}
