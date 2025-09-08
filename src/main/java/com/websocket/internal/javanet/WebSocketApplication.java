package com.websocket.internal.javanet;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.FileInputStream;
import java.net.*;
import java.security.KeyStore;
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
//        int tlsPort = 8443;
//        SSLServerSocket sslServer = createSslServerSocket(tlsPort, "keystore.jks", "password");
//        System.out.println("WSS server started on port " + tlsPort);
//
//        while(true) {
//            SSLSocket client = (SSLSocket) sslServer.accept();
//            // IMPORTANT: require handshake before using streams on some JVMs
//            client.setUseClientMode(false);
//            client.startHandshake(); // optional but good for early failures
//            // pass the SSLSocket into your handler (works same as plain Socket)
//            ClientHandler handler = new ClientHandler(client);
//            clients.add(handler);
//            executor.execute(handler);
//        }

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

    public static SSLServerSocket createSslServerSocket(int port,
          String keystorePath, String keystorePassword) throws Exception {
        // command to create keystore

//        keytool -genkeypair -alias wss-test -keyalg RSA -keysize 2048 \
//        -keystore keystore.jks -validity 3650 \
//        -storepass changeit -keypass changeit \
//        -dname "CN=localhost, OU=dev, O=me, L=City, S=State, C=US"

        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, keystorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword.toCharArray());

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
        SSLServerSocket ss = (SSLServerSocket) ssf.createServerSocket(port);
        // Optional: restrict TLS versions/ciphers for tests
        ss.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        return ss;
    }
}

//Add TLS (wss://) support (wrap sockets with SSL), or
//Add authentication and per-client rooms/channels for the broadcast server.