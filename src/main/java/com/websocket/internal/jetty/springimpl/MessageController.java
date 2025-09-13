package com.websocket.internal.jetty.springimpl;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class MessageController {

    @MessageMapping("/send")
    @SendTo("/topic/messages")
    public String broadcastMessage(String message) {
        return "Server broadcast: " + message;
    }
}
