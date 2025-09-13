package com.websocket.internal.jetty.springimpl.stomp;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomRegistry roomRegistry;

    public ChatController(SimpMessagingTemplate messagingTemplate, RoomRegistry roomRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.roomRegistry = roomRegistry;
    }

    @MessageMapping("/chat") // client -> /app/chat
    public void processMessage(@Payload ChatMessage message, Principal principal) {
        System.out.printf("Type: %s, From: %s, To: %s, RoomId: %s, Content: %s%n",
              message.getType(), principal.getName(), message.getTo(), message.getRoomId(),
              message.getContent());

        if ("JOIN".equalsIgnoreCase(message.getType()) && message.getRoomId() != null) {
            this.joinRoom(message);
        } else if ("LEAVE".equalsIgnoreCase(message.getType()) && message.getRoomId() != null) {
            this.leaveRoom(message);
        } else if (message.getRoomId() != null && !message.getRoomId().isBlank()) {
            // Send to a specific room
            messagingTemplate.convertAndSend("/topic/room/" + message.getRoomId(), message);
        } else if (message.getTo() != null && !message.getTo().isBlank()) {
            // Private chat
            messagingTemplate.convertAndSendToUser(message.getTo(), "/queue/private", message);
        } else {
            // Broadcast
            messagingTemplate.convertAndSend("/topic/public", message);
        }
    }

    private void joinRoom(ChatMessage message) {
        roomRegistry.joinRoom(message.getRoomId(), message.getFrom());
        message.setContent(message.getFrom() + " joined the room.");
        messagingTemplate.convertAndSend("/topic/room/" + message.getRoomId(), message);
    }

    private void leaveRoom(ChatMessage message) {
        roomRegistry.leaveRoom(message.getRoomId(), message.getFrom());
        message.setContent(message.getFrom() + " left the room.");
        messagingTemplate.convertAndSend("/topic/room/" + message.getRoomId(), message);
    }
}
