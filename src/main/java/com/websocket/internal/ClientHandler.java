package com.websocket.internal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

//      HEX BINARY

//      0	0000
//      1	0001
//      2	0010
//      3	0011
//      4	0100
//      5	0101
//      6	0110
//      7	0111
//      8	1000
//      9	1001
//      A	1010
//      B	1011
//      C	1100
//      D	1101
//      E	1110
//      F	1111


class ClientHandler implements Runnable {
    private static final int CTRL_MAX_LEN = 125;           // RFC: control frames ≤ 125
    private static final long MAX_MESSAGE_BYTES = 8 * 1024 * 1024; // 8 MB assembled message limit (tune for your needs)

    private final Socket client;
    private InputStream is;
    private OutputStream out;

    // Fragmentation state
    private boolean assembling = false;    // currently assembling a fragmented message?
    private int assemblingType = 0;        // 1=text, 2=binary
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);

    public ClientHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            this.is = client.getInputStream();
            this.out = client.getOutputStream();

            // --- Handshake ---
            System.out.println("[HANDSHAKE] Starting handshake with client " + client.getRemoteSocketAddress());
            BufferedReader in = new BufferedReader(new InputStreamReader(is));
            String line, webSocketKey = null;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, "Sec-WebSocket-Key:".length())) {
                    webSocketKey = line.split(":", 2)[1].trim();
                }
            }
            if (webSocketKey == null) throw new IOException("Missing Sec-WebSocket-Key");

            String acceptKey = Base64.getEncoder().encodeToString(
                  MessageDigest.getInstance("SHA-1")
                        .digest((webSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));

            String response =
                  "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes("US-ASCII"));
            out.flush();
            System.out.println("[HANDSHAKE] Handshake completed successfully with " + client.getRemoteSocketAddress());

            // --- Frame loop ---
            while (true) {
                int b1 = readByte();
                int b2 = readByte();
                // 0x80    1000 0000
                // 0x40    0100 0000
                // 0x40    0010 0000
                // 0x40    0001 0000
                // 0x0F    0000 1111
                // 0x7F    0111 1111
                boolean fin     = (b1 & 0x80) != 0;
                boolean rsv1    = (b1 & 0x40) != 0;
                boolean rsv2    = (b1 & 0x20) != 0;
                boolean rsv3    = (b1 & 0x10) != 0;
                int opcode      = (b1 & 0x0F);

                boolean masked  = (b2 & 0x80) != 0;
                long len7       = (b2 & 0x7F);
                long payloadLen = decodeExtendedLength(len7);

                System.out.printf("[FRAME] Received frame: FIN=%s, OPCODE=0x%X, MASKED=%s, PayloadLen=%d%n",
                      fin, opcode, masked, payloadLen);

                if (!masked) {
                    // Client-to-server frames MUST be masked per RFC 6455
                    throw new IOException("Protocol error: unmasked client frame");
                }
                if (rsv1 || rsv2 || rsv3) {
                    // No extensions negotiated → RSV must be 0
                    throw new IOException("Protocol error: RSV bits set");
                }

                // Control frames (like 0x8 → connection close, 0x9 → ping, 0xA → pong): not fragmented, ≤125 bytes
                // 0x08 = 0000 1000
                // 0x1  = 0000 0001
                boolean isControl = (opcode & 0x08) != 0;
                if (isControl) {
                    if (!fin) throw new IOException("Protocol error: fragmented control frame");
                    if (payloadLen > CTRL_MAX_LEN) throw new IOException("Protocol error: control frame too large");
                }

                // Read mask
                byte[] mask = readNByte(4);
                System.out.println("[FRAME] Mask key=" + Arrays.toString(mask));

                // Read payload (careful with large values; here we cap message size globally)
                if (!isControl && (assembling ? (buffer.size() + payloadLen) : payloadLen) > MAX_MESSAGE_BYTES) {
                    throw new IOException("Message too large (limit " + MAX_MESSAGE_BYTES + " bytes)");
                }
                byte[] payload = readNByte((int)payloadLen);

                // Unmask in-place
                for (int i = 0; i < payload.length; i++) {
                    // Both below condition are equivalent (i % 4 == i & 3)
                    //payload[i] = (byte)(payload[i] ^ mask[i % 4]);
                    payload[i] = (byte)(payload[i] ^ mask[i & 3]);
                }
                System.out.println("[FRAME] Payload (unmasked)=" + Arrays.toString(payload));

                // Handle opcodes
                switch (opcode) {
                    case 0x1: // Text frame (may be fragmented if FIN=0)
                    case 0x2: // Binary frame (may be fragmented if FIN=0)
                        if (assembling) {
                            // Cannot start a new message while one is in progress
                            throw new IOException("Protocol error: new data frame while continuation expected");
                        }
                        if (fin) {
                            // Single-frame message
                            deliver(opcode, payload);
                        } else {
                            System.out.println("[FRAGMENT] Starting text/binary frame assembly");
                            // Start fragmented message
                            startAssembly(opcode);
                            appendAssembly(payload);
                        }
                        break;

                    case 0x0: // Continuation
                        if (!assembling) throw new IOException("Protocol error: unexpected continuation frame");
                        System.out.println("[FRAGMENT] Appending continuation frame");
                        appendAssembly(payload);
                        if (fin) {
                            System.out.println("[FRAGMENT] Final fragment received, delivering message");
                            int tempAssemblingType = assemblingType == 0x1 ? 0x1 : 0x2;
                            byte[] full = endAssembly();
                            deliver(tempAssemblingType == 0x1 ? 0x1 : 0x2, full);
                        }
                        break;

                    case 0x8: // Close
                        System.out.println("[CONTROL] Close frame received");
                        handleClose(payload);
                        return;

                    case 0x9: // Ping → respond with Pong (echo payload)
                        System.out.println("[CONTROL] Ping frame received, sending Pong");
                        sendFrame((byte)0xA, payload);
                        break;

                    case 0xA: // Pong
                        System.out.println("[CONTROL] Pong frame received");
                        // optional: track pings/pongs
                        break;

                    default:
                        // Unknown/unsupported opcode → fail the connection
                        throw new IOException("Unsupported opcode: " + opcode);
                }
            }
        } catch (EOFException eof) {
            // client closed socket
            System.out.println("[CONNECTION] Client closed connection: " + client.getRemoteSocketAddress());
        } catch (Exception e) {
            System.out.println("Client " + client.getRemoteSocketAddress() + " error: " + e.getMessage());
        } finally {
            close();
        }
    }

    /* ------------------------ Fragmentation helpers ------------------------ */

    private void startAssembly(int opcode) {
        assembling = true;
        assemblingType = opcode; // 0x1 text, 0x2 binary
        buffer.reset();
        System.out.println("[FRAGMENT] Assembly started, type=" + (opcode == 0x1 ? "TEXT" : "BINARY"));
    }

    private void appendAssembly(byte[] chunk) throws IOException {
        if ((buffer.size() + (long)chunk.length) > MAX_MESSAGE_BYTES) {
            throw new IOException("Message too large while assembling");
        }
        buffer.write(chunk);
        System.out.println("[FRAGMENT] Appended " + chunk.length + " bytes, total=" + buffer.size());
    }

    private byte[] endAssembly() {
        assembling = false;
        int type = assemblingType;
        assemblingType = 0;
        byte[] data = buffer.toByteArray();
        buffer.reset();
        System.out.println("[FRAGMENT] Assembly complete, total size=" + data.length);
        return data;
    }

    private void deliver(int opcode, byte[] data) {
        if (opcode == 0x1) {
            // Text: validate UTF-8 across whole message
            if (!isValidUtf8(data)) {
                // Per RFC, invalid UTF-8 → close with 1007 (invalid payload data)
                sendClose(1007, "Invalid UTF-8 in text message");
                close();
                return;
            }
            String msg = new String(data, StandardCharsets.UTF_8);
            System.out.println("Text (" + msg.length() + " chars) from " + client.getRemoteSocketAddress() + ": " + msg);
            WebSocketApplication.broadcast(msg, this);
        } else if (opcode == 0x2) {
            System.out.println("Binary (" + data.length + " bytes) from " + client.getRemoteSocketAddress());
            WebSocketApplication.broadcastBinary(data, this);
        }
    }

    /* ------------------------ Frame send helpers ------------------------ */

    public void sendText(String message) {
        try {
            sendFrame((byte)0x1, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // ignore send errors; connection may be closing
        }
    }

    public void sendBinary(byte[] bytes) {
        try {
            sendFrame((byte)0x2, bytes);
        } catch (IOException e) { /* ignore */ }
    }

    private void sendClose(int code, String reason) {
        try {
            System.out.println("[CLOSE] Sending close frame code=" + code + ", reason=" + reason);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write((code >> 8) & 0xFF);
            baos.write(code & 0xFF);
            if (reason != null && !reason.isEmpty()) {
                byte[] r = reason.getBytes(StandardCharsets.UTF_8);
                baos.write(r, 0, Math.min(r.length, CTRL_MAX_LEN - 2)); // ensure ≤125 total
            }
            sendFrame((byte)0x8, baos.toByteArray());
        } catch (IOException ignored) {}
    }

    private void handleClose(byte[] payload) throws IOException {
        int code = 1000; // normal closure default
        String reason = "";
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (payload.length > 2) {
                byte[] r = Arrays.copyOfRange(payload, 2, payload.length);
                if (!isValidUtf8(r)) code = 1007;
                else reason = new String(r, StandardCharsets.UTF_8);
            }
        }
        System.out.println("[CLOSE] Received close frame: code=" + code + ", reason=" + reason);
        // Echo close
        sendClose(code, reason);
    }

    private void sendFrame(byte opcode, byte[] payload) throws IOException {
        System.out.printf("[SEND] Sending frame: OPCODE=0x%X, PayloadLen=%d%n", opcode, payload.length);
        // Server-to-client: not masked
        out.write(0x80 | (opcode & 0x0F)); // FIN=1
        int len = payload.length;
        if (len <= 125) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int)((long)len >>> (8 * i)) & 0xFF);
            }
        }
        out.write(payload);
        out.flush();
    }

    /* ------------------------ IO + utils ------------------------ */

    private int readByte() throws IOException {
        int b = is.read();
        if (b == -1) throw new EOFException();
        return b & 0xFF;
    }

    private byte[] readNByte(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r == -1) throw new EOFException();
            off += r;
        }
        return buf;
    }

    private static boolean isValidUtf8(byte[] data) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            dec.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private long decodeExtendedLength(long len7) throws IOException {
        long payloadLen = len7;
        if (len7 == 126) {
            payloadLen = ((readByte() & 0xFF) << 8) | (readByte() & 0xFF);
        } else if (len7 == 127) {
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (readByte() & 0xFF);
            }
            // Per RFC: the most significant bit MUST be 0 (i.e., lengths must be < 2^63)
            if ((payloadLen & (1L << 63)) != 0) throw new IOException("Invalid 64-bit payload length (MSB set)");
        }
        return payloadLen;
    }

    private void close() {
        try { client.close(); } catch (IOException ignored) {}
        System.out.println("[CONNECTION] Closed " + client.getRemoteSocketAddress());
        WebSocketApplication.clients.remove(this);
    }
}