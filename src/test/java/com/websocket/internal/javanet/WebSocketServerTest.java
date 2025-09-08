package com.websocket.internal.javanet;

import org.junit.jupiter.api.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WebSocketServerTest {

    private Thread serverThread;
    private final int PORT = 8080;
    private static final String HOST = "localhost";

    @BeforeAll
    void startServer() {
        serverThread = new Thread(() -> {
            try {
                WebSocketApplication.main(new String[]{});
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
    }

    @AfterAll
    void stopServer() {
        serverThread.interrupt();
    }

    public static SSLSocket createTrustAllSocket(String host, int port) throws Exception {
        TrustManager[] trustAll = new TrustManager[] {
              new X509TrustManager() {
                  public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                  public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                  public void checkServerTrusted(X509Certificate[] certs, String authType) {}
              }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new SecureRandom());
        SSLSocketFactory sf = sc.getSocketFactory();
        SSLSocket s = (SSLSocket) sf.createSocket(host, port);
        s.setEnabledProtocols(new String[] { "TLSv1.2", "TLSv1.3" });
        s.startHandshake();
        return s;
    }


    private Socket doHandshake(String path, Map<String, String> extraHeaders) throws Exception {
        // SSL Socket
//        SSLSocket socket = createTrustAllSocket(HOST, 8443);
        Socket socket = new Socket(HOST, PORT);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String wsKey = Base64.getEncoder().encodeToString("testkey123".getBytes(StandardCharsets.UTF_8));

        StringBuilder request = new StringBuilder();
        request.append("GET " + path + " HTTP/1.1\r\n");
        request.append("Host: " + HOST + ":" + PORT + "\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: " + wsKey + "\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");

        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                request.append(entry.getKey() + ": " + entry.getValue() + "\r\n");
            }
        }

        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String statusLine = reader.readLine();
        assertTrue(statusLine.contains("101"), "Expected 101 Switching Protocols");

        // consume headers
        String line;
        while (!(line = reader.readLine()).isEmpty()) {
            System.out.println("[HANDSHAKE HEADER] " + line);
        }
        return socket;
    }

    private void sendFrame(OutputStream out, int opcode, boolean fin, byte[] payload, boolean mask, boolean rsv1) throws IOException {
        int b1 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
        if (rsv1) {
            b1 |= 0x40;
        }
        out.write(b1);

        int length = payload.length;
        if (mask) {
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
        } else {
            if (length <= 125) {
                out.write(length);
            } else if (length <= 0xFFFF) {
                out.write(126);
                out.write((length >>> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((length >>> (8 * i)) & 0xFF);
                }
            }
        }

        byte[] maskKey = {1, 2, 3, 4};
        if (mask) {
            out.write(maskKey);
            byte[] masked = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
            out.write(masked);
        } else {
            out.write(payload);
        }
        out.flush();
    }

    private byte[] readFrame(InputStream in) throws IOException {
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
        return payload;
    }

    // similar to below function just that it has payload length handling
    private CloseFrame readCloseFrame(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();

        int b1 = in.read();
        int b2 = in.read();
        if (b1 == -1 || b2 == -1) return null;

        int opcode = b1 & 0x0F;
        if (opcode != 0x8) {
            throw new IOException("Expected close frame, got opcode=" + opcode);
        }

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

        CloseFrame frame = new CloseFrame();
        if (payload.length >= 2) {
            frame.code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (payload.length > 2) {
                frame.reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
            } else {
                frame.reason = "";
            }
        } else {
            frame.code = -1;
            frame.reason = "";
        }

        return frame;
    }

    private static class CloseFrame {
        int code;
        String reason;
    }

//    private String readCloseFrame(InputStream in) throws Exception {
//        int b1 = in.read();
//        int b2 = in.read();
//        int len = b2 & 0x7F;
//        byte[] payload = in.readNBytes(len);
//        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
//        String reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
//        return code + " " + reason;
//    }

    // Inflate permessage-deflate payload (no_context_takeover). Browsers usually send raw DEFLATE data
    // terminated with 0x00 0x00 0xff 0xff removed per RFC 7692. Many clients include that tail; if present we handle both.
    private byte[] inflatePerMessage(byte[] compressed) throws IOException, DataFormatException {
        // Some implementations omit the RFC7692 tail; some include it. Try both ways.
        // We'll first try with a zlib wrapper (nowrap=false) fallback to nowrap=true if needed.
        // But permessage-deflate expects raw DEFLATE (no zlib header). We'll try nowrap=true by default.

        Inflater inflater = new Inflater(true); // nowrap=true
        inflater.setInput(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        while (!inflater.finished() && !inflater.needsInput()) {
            int r = inflater.inflate(tmp);
            if (r == 0) break;
            baos.write(tmp, 0, r);
        }
        // If inflater didn't finish (maybe missing tail), try adding the 0x00 0x00 0xff 0xff tail (RFC tweak)
        if (!inflater.finished()) {
            inflater.end();
            byte[] withTail = new byte[compressed.length + 4];
            System.arraycopy(compressed, 0, withTail, 0, compressed.length);
            // RFC 1951 flush bytes
            withTail[compressed.length] = 0x00;
            withTail[compressed.length+1] = 0x00;
            withTail[compressed.length+2] = (byte)0xFF;
            withTail[compressed.length+3] = (byte)0xFF;
            inflater = new Inflater(true);
            inflater.setInput(withTail);
            baos.reset();
            while (!inflater.finished()) {
                int r = inflater.inflate(tmp);
                if (r == 0) break;
                baos.write(tmp, 0, r);
            }
        }
        inflater.end();
        return baos.toByteArray();
    }

    // Deflate payload for permessage-deflate (no_context_takeover). We produce raw DEFLATE bytes (nowrap=true).
    private byte[] deflatePerMessage(byte[] data) throws IOException {
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true); // nowrap=true
        def.setInput(data);
        def.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        while (!def.finished()) {
            int r = def.deflate(tmp);
            baos.write(tmp, 0, r);
        }
        def.end();
        byte[] outBytes = baos.toByteArray();
        // Many implementations require removal of trailing 0x00 0x00 0xFF 0xFF (RFC7692) — but behavior varies.
        // For client compatibility, remove last 4 bytes if present and equal to 0x00 0x00 0xFF 0xFF
        if (outBytes.length >= 4) {
            int n = outBytes.length;
            if ((outBytes[n-4] == 0x00) && (outBytes[n-3] == 0x00) && (outBytes[n-2] == (byte)0xFF) && (outBytes[n-1] == (byte)0xFF)) {
                return Arrays.copyOf(outBytes, n-4);
            }
        }
        return outBytes;
    }



    // ===================== POSITIVE TESTS =====================
    @Test @Order(1)
    public void testSimpleTextFrame() throws Exception {
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        String msg = "Hello WebSocket";
        sendFrame(out, 0x1, true, msg.getBytes(StandardCharsets.UTF_8), true, false);

        byte[] echoed = readFrame(in);
        assertEquals(msg, new String(echoed, StandardCharsets.UTF_8));
        socket.close();
    }

    @Test @Order(2)
    public void testFragmentedTextFrame() throws Exception {
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        sendFrame(out, 0x1, false, "Hello ".getBytes(StandardCharsets.UTF_8), true, false);
        sendFrame(out, 0x0, true, "World".getBytes(StandardCharsets.UTF_8), true, false);

        byte[] echoed = readFrame(in);
        assertEquals("Hello World", new String(echoed, StandardCharsets.UTF_8));
        socket.close();
    }

    @Test @Order(3)
    public void testPingPong() throws Exception {
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        sendFrame(out, 0x9, true, "ping".getBytes(StandardCharsets.UTF_8), true, false);
        byte[] pong = readFrame(in);
        assertEquals("ping", new String(pong, StandardCharsets.UTF_8));
        socket.close();
    }

    @Test @Order(4)
    public void testCloseFrame() throws Exception {
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        sendFrame(out, 0x8, true, new byte[]{(byte)0x03, (byte)0xE8}, true, false);
        byte[] echoed = readFrame(in);
        assertNotNull(echoed);
        socket.close();
    }

    @Test
    @Order(5)
    public void testBinaryFrame() throws Exception {
        System.out.println("=== TEST: BINARY FRAME ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        sendFrame(out, 0x2, true, payload, true, false); // opcode=2 = binary

        byte[] echoed = readFrame(socket.getInputStream());
        assertArrayEquals(payload, echoed, "Server must echo back binary data");
        socket.close();
    }

    @Test
    @Order(6)
    public void testFragmentedBinaryFrame() throws Exception {
        System.out.println("=== TEST: FRAGMENTED BINARY FRAME ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        byte[] part1 = new byte[]{1, 2};
        byte[] part2 = new byte[]{3, 4, 5};

        // Send fragmented binary (opcode=2 + FIN=false, then continuation)
        sendFrame(out, 0x2, false, part1, true, false);
        sendFrame(out, 0x0, true, part2, true, false);

        byte[] echoed = readFrame(socket.getInputStream());
        byte[] combined = new byte[]{1, 2, 3, 4, 5};
        assertArrayEquals(combined, echoed, "Server must reassemble fragmented binary frame");
        socket.close();
    }

    @Test
    @Order(7)
    public void testCompressedTextFrame() throws Exception {
        System.out.println("=== TEST: COMPRESSED TEXT FRAME ===");
        Socket socket = doHandshake("/", Map.of("sec-websocket-extensions","permessage-deflate"));
        OutputStream out = socket.getOutputStream();

        String message = "Hello compressed world!";
        byte[] compressed = deflatePerMessage(message.getBytes(StandardCharsets.UTF_8));

        // Send compressed text frame with RSV1 = 1
        sendFrame(out, 0x1, true, compressed, true, true);

        // Server should decompress and echo/broadcast back
        byte[] echoed = readFrame(socket.getInputStream());
        byte[] uncompressedMessage = inflatePerMessage(echoed);
        assertNotNull(echoed, "Echoed message should not be null");
        String echoedMsg = new String(uncompressedMessage, StandardCharsets.UTF_8);
        assertEquals(message, echoedMsg, "Server should echo decompressed payload");

        socket.close();
    }

    // ===================== NEGATIVE TESTS =====================
    @Test @Order(8)
    void testUnmaskedFrame() throws Exception {
        System.out.println("=== TEST: UNMASKED FRAME ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        // Build an invalid unmasked frame (opcode 1 = text)
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);  // FIN + text opcode
        frame.write(0x05);  // no MASK bit set, payload len = 5
        frame.write("Hello".getBytes(StandardCharsets.UTF_8));
        out.write(frame.toByteArray());
        out.flush();

        // Expect close frame from server
        CloseFrame close = readCloseFrame(socket);
        assertNotNull(close);
        System.out.println("[ASSERT] closeCode=" + close.code + ", reason=" + close.reason);

        assertEquals(1002, close.code, "Unmasked frame must close with protocol error 1002");
        assertEquals("Client frames MUST be masked", close.reason);
        socket.close();
    }


    @Test
    @Order(9)
    public void testOversizedControlFrame() throws Exception {
        System.out.println("=== TEST: OVERSIZED CONTROL FRAME ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        byte[] tooBig = new byte[200]; // control frames must be <= 125
        sendFrame(out, 0x9, true, tooBig, true, false); // opcode=9 = PING

        CloseFrame frame = readCloseFrame(socket);
        System.out.println("[ASSERT] code=" + frame.code + ", reason=" + frame.reason);

        assertEquals(1002, frame.code, "Oversized control frame must close with 1002 (protocol error)");
        assertTrue(frame.reason.contains("Control frame too large"), "Expected reason about oversized control frame");
        socket.close();
    }

    @Test @Order(10)
    public void testInvalidUtf8InText() throws Exception {
        System.out.println("=== TEST: INVALID UTF-8 IN TEXT ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        byte[] invalid = {(byte)0xC3, (byte)0x28}; // invalid UTF-8 sequence
        sendFrame(out, 0x1, true, invalid, true, false); // opcode=1 = TEXT

        CloseFrame frame = readCloseFrame(socket);
        System.out.println("[ASSERT] code=" + frame.code + ", reason=" + frame.reason);

        assertEquals(1007, frame.code, "Invalid UTF-8 must close with 1007 (invalid payload data)");
        assertTrue(frame.reason.contains("Invalid UTF-8"), "Expected reason mentioning invalid UTF-8");
        socket.close();
    }

    @Test @Order(11)
    public void testReservedBitSet() throws Exception {
        System.out.println("=== TEST: RSV BIT SET ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        // FIN=1, RSV1=1, opcode=1 (text)
        int b1 = 0x80 | 0x40 | 0x1;
        out.write(b1);
        out.write(0x81); // MASK=1, length=1
        out.write(new byte[]{1, 2, 3, 4}); // masking key
        out.write(0x41 ^ 1); // masked payload
        out.flush();

        CloseFrame frame = readCloseFrame(socket);
        System.out.println("[ASSERT] code=" + frame.code + ", reason=" + frame.reason);

        assertEquals(1002, frame.code, "RSV bit set must close with 1002 (protocol error)");
        assertTrue(frame.reason.contains("RSV"), "Expected reason mentioning RSV bits");
        socket.close();
    }

    @Test @Order(12)
    public void testInvalidOpcode() throws Exception {
        System.out.println("=== TEST: INVALID OPCODE ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        // FIN + opcode=0xB (reserved/invalid)
        sendFrame(out, 0xB, true, "oops".getBytes(StandardCharsets.UTF_8), true, false);

        CloseFrame close = readCloseFrame(socket);
        System.out.println("[ASSERT] code=" + close.code + ", reason=" + close.reason);

        assertEquals(1003, close.code, "Invalid opcode must trigger 1003 (unsupported data)");
        assertTrue(close.reason.toLowerCase().contains("unsupported"),
              "Expected reason mentioning unsupported opcode");
        socket.close();
    }

    @Test @Order(13)
    void testUnexpectedContinuationRejected() throws Exception {
        System.out.println("=== TEST: INVALID CONTINUATION ===");
        Socket socket = doHandshake("/", null);
        OutputStream out = socket.getOutputStream();

        // CONTINUATION frame without preceding TEXT/BINARY
        sendFrame(out, 0x80, true, "oops".getBytes(StandardCharsets.UTF_8), true, false);

        CloseFrame close = readCloseFrame(socket);
        System.out.println("[ASSERT] code=" + close.code + ", reason=" + close.reason);

        assertEquals(1002, close.code, "Invalid continuation must trigger 1002");
        assertTrue(close.reason.contains("Unexpected continuation"), "Expected 1002 for unexpected CONTINUATION");
        socket.close();
    }
}
