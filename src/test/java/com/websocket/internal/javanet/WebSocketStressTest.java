package com.websocket.internal.javanet;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.*;

/**
 * Stress test for WebSocket server:
 * - Launches N clients in parallel
 * - Each client performs handshake
 * - Each client sends large fragmented payloads
 * - Logs results and verifies echoes
 */
//@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WebSocketStressTest {

//    private Thread serverThread;
//
//    @BeforeAll
//    void startServer() {
//        serverThread = new Thread(() -> {
//            try {
//                WebSocketApplication.main(new String[]{});
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        });
//        serverThread.setDaemon(true);
//        serverThread.start();
//
//        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
//    }
//
//    @AfterAll
//    void stopServer() {
//        serverThread.interrupt();
//    }

    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final int CLIENT_COUNT = 50;   // number of concurrent clients
    private static final int PAYLOAD_SIZE = 64 * 1024; // 64 KB per client

    private static Socket doHandshake() throws Exception {
        Socket socket = new Socket(HOST, PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String wsKey = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        StringBuilder request = new StringBuilder();
        request.append("GET / HTTP/1.1\r\n");
        request.append("Host: " + HOST + ":" + PORT + "\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: " + wsKey + "\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        request.append("\r\n");

        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String statusLine = reader.readLine();
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("Handshake failed: " + statusLine);
        }

        // consume headers
        String line;
        while (!(line = reader.readLine()).isEmpty()) {
            // ignore
        }
        return socket;
    }

    private static void sendFragmentedFrame(OutputStream out, byte[] payload, int fragmentSize) throws IOException {
        int offset = 0;
        boolean first = true;
        while (offset < payload.length) {
            int remaining = payload.length - offset;
            int chunkSize = Math.min(fragmentSize, remaining);
            boolean fin = (offset + chunkSize) >= payload.length;
            int opcode = first ? 0x1 : 0x0; // first frame = text, rest = continuation
            sendFrame(out, opcode, fin, Arrays.copyOfRange(payload, offset, offset + chunkSize));
            offset += chunkSize;
            first = false;
        }
    }

    private static void sendFrame(OutputStream out, int opcode, boolean fin, byte[] payload) throws IOException {
        int b1 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
        out.write(b1);

        int length = payload.length;
        if (length <= 125) {
            out.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xFF);
            }
        }

        byte[] maskKey = {5, 6, 7, 8};
        out.write(maskKey);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ maskKey[i % 4]);
        }
        out.flush();
    }

    private static String readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 == -1 || b2 == -1) return null;

        int len = b2 & 0x7F;
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | (in.read() & 0xFF);
            }
            len = (int) l;
        }

        byte[] payload = in.readNBytes(len);
        return new String(payload, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch latch = new CountDownLatch(CLIENT_COUNT);

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final int clientId = i;
            pool.submit(() -> {
                try {
                    Socket socket = doHandshake();
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    // Create large payload
                    String message = "Client-" + clientId + " " + "X".repeat(PAYLOAD_SIZE - 10);
                    byte[] data = message.getBytes(StandardCharsets.UTF_8);

                    // Send fragmented frame in 8 KB chunks
                    sendFragmentedFrame(out, data, 8 * 1024);

                    // Read echo back
                    String echoed = readFrame(in);
                    if (echoed != null && echoed.startsWith("Client-" + clientId)) {
                        System.out.println("[CLIENT " + clientId + "] SUCCESS echoed payload length=" + echoed.length());
                    } else {
                        System.err.println("[CLIENT " + clientId + "] FAIL echoed=" + echoed);
                    }

                    socket.close();
                } catch (Exception e) {
                    System.err.println("[CLIENT " + clientId + "] ERROR: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        System.out.println("=== Stress test completed with " + CLIENT_COUNT + " clients ===");
    }
}
