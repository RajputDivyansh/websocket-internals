package com.websocket.internal.jetty.springimpl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JettyWebsocketApplication {
    public static void main(String[] args) {
        SpringApplication.run(JettyWebsocketApplication.class, args);
    }
}
