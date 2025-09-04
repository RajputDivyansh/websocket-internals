package com.websocket.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

public class WebSocketClient {

    private final String host;
    private final int port;
    private final String path;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final Random random = new SecureRandom();

    public WebSocketClient(String host, int port, String path) {
        this.host = host;
        this.port = port;
        this.path = path;
    }

    public void connect() throws Exception {
        socket = new Socket(host, port);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        // --- Handshake ---
        String key = Base64.getEncoder().encodeToString(new byte[16]);
        String req = "GET " + path + " HTTP/1.1\r\n" +
              "Host: " + host + ":" + port + "\r\n" +
              "Upgrade: websocket\r\n" +
              "Connection: Upgrade\r\n" +
              "Sec-WebSocket-Key: " + key + "\r\n" +
              "Sec-WebSocket-Version: 13\r\n\r\n";

        out.write(req.getBytes("UTF-8"));
        out.flush();

        byte[] buf = new byte[1024];
        int read = in.read(buf);
        String resp = new String(buf, 0, read);
        System.out.println("[CLIENT-HANDSHAKE] Response:\n" + resp);
    }

    /* ---------------- Frame send helpers ---------------- */

    public void sendText(String msg) throws Exception {
        System.out.println("[CLIENT-SEND] Sending text: " + msg);
        sendFrame((byte) 0x1, msg.getBytes("UTF-8"));
    }

    public void sendBinary(byte[] data) throws Exception {
        System.out.println("[CLIENT-SEND] Sending binary of " + data.length + " bytes");
        sendFrame((byte) 0x2, data);
    }

    public void sendFragmentedText(String msg, int chunkSize) throws Exception {
        System.out.println("[CLIENT-SEND] Sending fragmented text: " + msg + " (chunkSize=" + chunkSize + ")");
        byte[] all = msg.getBytes("UTF-8");

        // First frame (FIN=0, opcode=1)
        int firstLen = Math.min(chunkSize, all.length);
        sendFrameFragment((byte) 0x1, false, all, 0, firstLen);

        int offset = firstLen;
        while (offset < all.length) {
            int len = Math.min(chunkSize, all.length - offset);
            boolean fin = (offset + len == all.length);
            sendFrameFragment((byte) 0x0, fin, all, offset, len);
            offset += len;
        }
    }

    public void sendPing(String msg) throws Exception {
        System.out.println("[CLIENT-SEND] Sending ping: " + msg);
        sendFrame((byte) 0x9, msg.getBytes("UTF-8"));
    }

    public void sendClose(int code, String reason) throws Exception {
        System.out.println("[CLIENT-SEND] Sending close: code=" + code + ", reason=" + reason);
        byte[] reasonBytes = reason.getBytes("UTF-8");
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((code >> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        sendFrame((byte) 0x8, payload);
    }

    private void sendFrame(byte opcode, byte[] payload) throws Exception {
        sendFrameInternal(opcode, true, payload, 0, payload.length);
    }

    private void sendFrameFragment(byte opcode, boolean fin, byte[] payload, int off, int len) throws Exception {
        sendFrameInternal(opcode, fin, payload, off, len);
    }

    private void sendFrameInternal(byte opcode, boolean fin, byte[] payload, int off, int len) throws Exception {
        byte b1 = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));
        out.write(b1);

        int length = len;
        if (length <= 125) {
            out.write(0x80 | length); // mask bit set
        } else if (length <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((long) length >>> (8 * i)) & 0xFF);
            }
        }

        // Mask key
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        out.write(mask);

        // Apply mask
        for (int i = 0; i < length; i++) {
            out.write(payload[off + i] ^ mask[i & 3]);
        }
        out.flush();
    }

    /* ---------------- Receiving ---------------- */

    public void listen() throws Exception {
        new Thread(() -> {
            try {
                while (true) {
                    int b1 = in.read();
                    if (b1 == -1) break;
                    int b2 = in.read();
                    boolean fin = (b1 & 0x80) != 0;
                    int opcode = b1 & 0x0F;
                    int len = b2 & 0x7F;

                    if (len == 126) {
                        len = (in.read() << 8) | in.read();
                    } else if (len == 127) {
                        len = 0;
                        for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                    }

                    byte[] data = new byte[len];
                    int off = 0;
                    while (off < len) {
                        int r = in.read(data, off, len - off);
                        if (r == -1) throw new RuntimeException("Stream ended");
                        off += r;
                    }

                    switch (opcode) {
                        case 0x1:
                            System.out.println("[CLIENT-RECV] Text: " + new String(data, "UTF-8"));
                            break;
                        case 0x2:
                            System.out.println("[CLIENT-RECV] Binary (" + data.length + " bytes)");
                            break;
                        case 0x8:
                            System.out.println("[CLIENT-RECV] Close frame received");
                            return;
                        case 0x9:
                            System.out.println("[CLIENT-RECV] Ping received");
                            break;
                        case 0xA:
                            System.out.println("[CLIENT-RECV] Pong received");
                            break;
                        default:
                            System.out.println("[CLIENT-RECV] Unknown opcode " + opcode);
                    }
                }
            } catch (Exception e) {
                System.out.println("[CLIENT-ERROR] " + e.getMessage());
            }
        }).start();
    }

    public static void main(String[] args) throws Exception {
        WebSocketClient client = new WebSocketClient("localhost", 8080, "/");
        client.connect();
        client.listen();

        // --- Test cases ---
        Thread.sleep(500);

        client.sendText("Hello Server!");
        Thread.sleep(500);

        client.sendBinary(new byte[]{1, 2, 3, 4, 5});
        Thread.sleep(500);

        client.sendFragmentedText("This is a fragmented message split into chunks.", 10);
        Thread.sleep(500);

        client.sendPing("Are you alive?");
        Thread.sleep(500);

        client.sendClose(1000, "Normal Closure");
    }
}
