package com.websocket.internal;

import java.net.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket broadcast server with:
 *  - ThreadPoolExecutor (bounded)
 *  - Fragmentation reassembly
 *  - permessage-deflate (no_context_takeover) negotiation + deflate/inflate
 *  - Improved close semantics
 *
 * Pragmatic implementation for learning / small deployments.
 */
public class WebSocketApplication {

    // Thread-safe client list
    public static final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    // Executor for client handlers
    private static final int CORE_THREADS = 4;
    private static final int MAX_THREADS = 32;
    private static final int QUEUE_CAPACITY = 200;
    private static final ExecutorService executor = new ThreadPoolExecutor(
          CORE_THREADS,
          MAX_THREADS,
          60L, TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          new ThreadPoolExecutor.AbortPolicy()
    );

    public static void main(String[] args) throws Exception {
        int port = 8080;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("WebSocket broadcast server (thread pool + permessage-deflate + fragmentation) on port " + port);
            while (true) {
                final Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                ClientHandler handler = new ClientHandler(client);
                clients.add(handler);
                executor.execute(handler);
            }
        } finally {
            executor.shutdown();
        }
    }

    public static void broadcast(String message, ClientHandler sender) {
        for (ClientHandler c : clients) {
            if (c == sender)
                c.sendText(message);
        }
    }

    public static void broadcastBinary(byte[] message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client == sender) { // skip echoing back to sender if you want
                client.sendBinary(message);
            }
        }
    }
}

//Add TLS (wss://) support (wrap sockets with SSL), or
//Add authentication and per-client rooms/channels for the broadcast server.