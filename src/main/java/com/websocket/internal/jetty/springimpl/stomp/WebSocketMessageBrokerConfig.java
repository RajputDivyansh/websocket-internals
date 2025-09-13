package com.websocket.internal.jetty.springimpl.stomp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketMessageBrokerConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");  // where clients subscribe
        config.setApplicationDestinationPrefixes("/app"); // prefix for sending
        config.setUserDestinationPrefix("/user");
    }

    @Bean
    public HandshakeHandler customHandshakeHandler() {
        return new DefaultHandshakeHandler() {
            @Override
            protected Principal determineUser(ServerHttpRequest request,
                  WebSocketHandler wsHandler,
                  Map<String, Object> attributes) {

                // Get username from query param
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    var params = servletRequest.getServletRequest().getParameter("username");
                    if (params != null && !params.isBlank()) {
                        System.out.println("🔐 Assigned username: " + params);
                        return () -> params;
                    }
                }

                return () -> null;
            }
        };
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-stomp", "/ws-chat")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(customHandshakeHandler())
                .withSockJS();
    }
}
