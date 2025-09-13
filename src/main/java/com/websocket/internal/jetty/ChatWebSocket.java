package com.websocket.internal.jetty;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;

@ServerEndpoint("/chat")
public class ChatWebSocket {

    @OnOpen
    public void onOpen(Session session) throws IOException {
        System.out.printf("Client connected: %s%n", session.getId());
        session.getBasicRemote().sendText("Welcome " + session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session) throws IOException {
        System.out.printf("Received message from client %s: %s%n", session.getId(), message);
        // Broadcast the message to all connected clients
//        session.getBasicRemote().sendText("Echo: " + message);
        for (Session peer : session.getOpenSessions()) {
            if (peer.isOpen()) {
                peer.getBasicRemote().sendText("Client " + session.getId() + ": " + message);
            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        System.out.printf("Client disconnected: %s%n", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.printf("Error on client %s: %s%n", session.getId(), throwable.toString());
    }
}