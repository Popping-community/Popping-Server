package com.example.popping.config.websocket;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import jakarta.servlet.http.HttpSession;

public class LoggingHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {
        boolean result = super.beforeHandshake(request, response, wsHandler, attributes);

        HttpSession session = null;
        if (request instanceof ServletServerHttpRequest) {
            session = ((ServletServerHttpRequest) request).getServletRequest().getSession(false);
        }

        return result;
    }
}