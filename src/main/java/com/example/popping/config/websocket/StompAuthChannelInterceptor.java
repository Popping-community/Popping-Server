package com.example.popping.config.websocket;

import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            Authentication auth = extractAuthentication(accessor);
            if (auth != null && auth.isAuthenticated()) {
                accessor.setUser(auth); // 이후 @MessageMapping에서 headerAccessor.getUser()로 접근 가능
            }
        }
        return message;
    }

    private Authentication extractAuthentication(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) return null;

        Object ctxObj = sessionAttributes.get(SECURITY_CONTEXT_ATTR);
        if (!(ctxObj instanceof SecurityContext context)) return null;

        return context.getAuthentication();
    }
}