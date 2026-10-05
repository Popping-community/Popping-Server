package com.example.popping.config.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.sockjs.transport.handler.WebSocketTransportHandler;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Same-origin only (Spring's default when no origins are set; a request without an
        // Origin header is not a browser and is let through). HAProxy passes Host through, so
        // the page's own origin matches today. If TLS is ever terminated in front of the apps,
        // they must trust the forwarded headers (server.forward-headers-strategy), or the
        // https Origin will not match the http request and every page will get 403.
        // WebSocket is the only SockJS transport. The XHR fallbacks keep their session in one
        // app's memory, and with two apps behind round robin the follow-up request lands on
        // the other app and gets 404 (measured 2026-10-05), so they never worked here. With
        // one transport, every new session - and the guest identity bound to it - is one
        // WebSocket upgrade, which HAProxy counts per address.
        registry.addEndpoint("/ws")
                .addInterceptors(new GuestIdentityHandshakeInterceptor())
                .withSockJS()
                .setTransportHandlers(new WebSocketTransportHandler(new DefaultHandshakeHandler()));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}