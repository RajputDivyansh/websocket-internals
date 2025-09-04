package com.websocket.internal;

import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class WebSocketApplication {
    // Thread-safe list of connected clients
    public static final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("WebSocket broadcast server (with fragmentation) on port " + port);

        while (true) {
            Socket client = serverSocket.accept();
            System.out.println("New client connected: " + client.getInetAddress());

            ClientHandler handler = new ClientHandler(client);
            clients.add(handler);
            new Thread(handler, "ws-client-" + client.getPort()).start();
        }
    }

    // Broadcast text to everyone except sender
    public static void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) { // skip echoing back to sender if you want
                client.sendText(message);
            }
        }
    }

    public static void broadcastBinary(byte[] message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) { // skip echoing back to sender if you want
                client.sendBinary(message);
            }
        }
    }
}