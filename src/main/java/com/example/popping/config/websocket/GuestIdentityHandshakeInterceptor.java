package com.example.popping.config.websocket;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.example.popping.filter.GuestIdentifierFilter;

/**
 * Carries the guest identity into the WebSocket session. The handshake is an ordinary HTTP
 * request, so {@link GuestIdentifierFilter} has already verified the signed cookie and put the
 * UUID on the request; STOMP handlers read it from the session attributes instead of trusting
 * anything the client writes into a frame.
 */
public class GuestIdentityHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {
        boolean proceed = super.beforeHandshake(request, response, wsHandler, attributes);
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Object guestUuid = servletRequest.getServletRequest()
                    .getAttribute(GuestIdentifierFilter.GUEST_UUID_ATTR);
            if (guestUuid instanceof String uuid) {
                attributes.put(GuestIdentifierFilter.GUEST_UUID_ATTR, uuid);
            }
        }
        return proceed;
    }
}
