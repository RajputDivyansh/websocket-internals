package com.websocket.internal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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


// 0x80    1000 0000
// 0x40    0100 0000
// 0x40    0010 0000
// 0x40    0001 0000
// 0x0F    0000 1111
// 0x7F    0111 1111

/* ---------------------------------------------------------------------------
   ClientHandler: handles handshake, frames, fragmentation, compression, closing
   --------------------------------------------------------------------------- */
public class ClientHandler implements Runnable {
    private static final int CTRL_MAX_LEN = 125;           // RFC: control frames ≤ 125
    private static final long MAX_MESSAGE_BYTES = 16 * 1024 * 1024; // 16 MB assembled message limit

    private final Socket client;
    private InputStream is;
    private OutputStream out;

    // Fragmentation assembly state
    private boolean assembling = false;
    private int assemblingType = 0; // 1 = text, 2 = binary
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);

    // Compression negotiation flag (permessage-deflate)
    private final AtomicBoolean perMessageDeflate = new AtomicBoolean(false);

    public ClientHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            this.is = client.getInputStream();
            this.out = client.getOutputStream();

            // Handshake and negotiate extensions
            System.out.println("[HANDSHAKE] Starting handshake with " + client.getRemoteSocketAddress());
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII));
            String line;
            String wsKey = null;
            String extensionsHeader = null;
            String origin = null;
            String requestedProtocol = null;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("sec-websocket-key:")) {
                    wsKey = line.split(":",2)[1].trim();
                } else if (low.startsWith("sec-websocket-extensions:")) {
                    extensionsHeader = line.split(":",2)[1].trim();
                } else if (low.startsWith("sec-websocket-protocol:")) {
                    requestedProtocol = line.split(":",2)[1].trim();
                } else if (low.startsWith("origin:")) {
                    origin = line.split(":",2)[1].trim();
                }
            }
            if (wsKey == null) throw new IOException("Missing Sec-WebSocket-Key");

            // Decide on permessage-deflate
            boolean clientRequestedPMD = false;
            if (extensionsHeader != null && extensionsHeader.toLowerCase(Locale.ROOT).contains("permessage-deflate")) {
                clientRequestedPMD = true;
            }

            // For simplicity: accept permessage-deflate with both no_context_takeover if client asked
            String serverExtensionsResponse = null;
            if (clientRequestedPMD) {
                perMessageDeflate.set(true);
                serverExtensionsResponse = "permessage-deflate; client_no_context_takeover; server_no_context_takeover";
            }

            // Build handshake response
            String accept = Base64.getEncoder().encodeToString(
                  MessageDigest.getInstance("SHA-1")
                        .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8))
            );
            StringBuilder resp = new StringBuilder();
            resp.append("HTTP/1.1 101 Switching Protocols\r\n");
            resp.append("Upgrade: websocket\r\n");
            resp.append("Connection: Upgrade\r\n");
            resp.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
            if (serverExtensionsResponse != null) {
                resp.append("Sec-WebSocket-Extensions: ").append(serverExtensionsResponse).append("\r\n");
            }
            // (Optional) Sec-WebSocket-Protocol if you want to accept subprotocols
            resp.append("\r\n");
            out.write(resp.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            System.out.println("Handshake OK (PMD=" + perMessageDeflate.get() + ") for " + client.getRemoteSocketAddress());

            // Frame loop
            while (true) {
                int b1 = readByte();
                int b2 = readByte();
                boolean fin = (b1 & 0x80) != 0;
                boolean rsv1 = (b1 & 0x40) != 0;
                boolean rsv2 = (b1 & 0x20) != 0;
                boolean rsv3 = (b1 & 0x10) != 0;
                int opcode = (b1 & 0x0F);
                boolean masked = (b2 & 0x80) != 0;
                long len7 = (b2 & 0x7F);
                long payloadLen = decodeExtendedLength(len7);

                // Validate RSV usage
                if ((rsv2 || rsv3)) {
                    System.out.println("[ERROR] RSV2/RSV3 not supported");
                    sendCloseAndRemove(1002, "RSV2/RSV3 not supported");
                    return;
                }
                // If RSV1 set, only acceptable when permessage-deflate negotiated
                if (rsv1 && !perMessageDeflate.get()) {
                    System.out.println("[ERROR] RSV1 without PMD negotiation");
                    sendCloseAndRemove(1002, "RSV1 set but permessage-deflate not negotiated");
                    return;
                }

                if (!masked) {
                    System.out.println("[ERROR] Client frame not masked");
                    sendCloseAndRemove(1002, "Client frames MUST be masked");
                    return;
                }

                boolean isControl = (opcode & 0x08) != 0;
                if (isControl) {
                    if (!fin) {
                        System.out.println("[ERROR] Control frame fragmented");
                        sendCloseAndRemove(1002, "Control frames must not be fragmented");
                        return;
                    }
                    if (payloadLen > CTRL_MAX_LEN) {
                        System.out.println("[ERROR] Control frame too large");
                        sendCloseAndRemove(1002, "Control frame too large");
                        return;
                    }
                }

                byte[] mask = readN(4);
                if (!isControl && (assembling ? (buffer.size() + payloadLen) : payloadLen) > MAX_MESSAGE_BYTES) {
                    sendCloseAndRemove(1009, "Message too large");
                    return;
                }
                byte[] payload = readN((int) payloadLen);

                // Unmask
                for (int i = 0; i < payload.length; i++) payload[i] = (byte)(payload[i] ^ mask[i & 3]);

                // If RSV1 and permessage-deflate: inflate (no_context_takeover semantics)
                if (rsv1 && perMessageDeflate.get()) {
                    payload = inflatePerMessage(payload);
                    System.out.println("[DECOMPRESS] Inflated payload, new len=" + payload.length);
                    // after deflate/inflate, RSV1 is considered consumed
                }

                switch (opcode) {
                    case 0x1: // text
                        System.out.println("[TEXT] Received frame, FIN=" + fin + ", len=" + payload.length);
                        if (assembling) {
                            sendCloseAndRemove(1002, "Received new data frame while continuation expected");
                            return;
                        }
                        if (fin) {
                            // single-frame text
                            if (!isValidUtf8(payload)) {
                                sendCloseAndRemove(1007, "Invalid UTF-8");
                                return;
                            }
                            String msg = new String(payload, StandardCharsets.UTF_8);
                            WebSocketApplication.broadcast(msg, this);
                            System.out.println("[TEXT] Complete message: " + msg);
                        } else {
                            startAssembly(0x1);
                            appendAssembly(payload);
                            System.out.println("[TEXT] Started fragmentation assembly");
                        }
                        break;

                    case 0x2: // binary
                        System.out.println("[BINARY] Received frame, FIN=" + fin + ", len=" + payload.length);
                        if (assembling) {
                            sendCloseAndRemove(1002, "Received new data frame while continuation expected");
                            return;
                        }
                        if (fin) {
                            System.out.println("[BINARY] Complete binary msg (" + payload.length + " bytes)");
                            WebSocketApplication.broadcastBinary(payload, this);
                        } else {
                            startAssembly(0x2);
                            appendAssembly(payload);
                            System.out.println("[BINARY] Started fragmentation assembly");
                        }
                        break;

                    case 0x0: // continuation
                        System.out.println("[CONTINUATION] Received, FIN=" + fin + ", len=" + payload.length);
                        if (!assembling) {
                            System.out.println("[ERROR] Unexpected continuation frame");
                            sendCloseAndRemove(1002, "Unexpected continuation");
                            return;
                        }
                        appendAssembly(payload);
                        if (fin) {
                            int tempAssemblingType = assemblingType;
                            byte[] full = endAssembly();
                            if (tempAssemblingType == 0x1) {
                                if (!isValidUtf8(full)) {
                                    sendCloseAndRemove(1007, "Invalid UTF-8 in reassembled text");
                                    return;
                                }
                                String msg = new String(full, StandardCharsets.UTF_8);
                                System.out.println("[CONTINUATION] Reassembled TEXT: " + msg);
                                WebSocketApplication.broadcast(msg, this);
                            } else {
                                System.out.println("[CONTINUATION] Reassembled BINARY (" + full.length + " bytes)");
                                sendBinary(full);
                            }
                        }
                        break;

                    case 0x8: // close
                        System.out.println("[CLOSE] Received close frame");
                        handleClose(payload);
                        return;

                    case 0x9: // ping
                        // pong must carry identical payload; pong must not be fragmented and ≤125
                        System.out.println("[PING] Received: " + new String(payload, StandardCharsets.UTF_8));
                        sendPong(payload);
                        break;

                    case 0xA: // pong
                        // ignore or use to track liveness
                        System.out.println("[PONG] Received: " + new String(payload, StandardCharsets.UTF_8));
                        break;

                    default:
                        System.out.println("[ERROR] Unsupported opcode " + opcode);
                        sendCloseAndRemove(1003, "Unsupported opcode");
                        return;
                }
            }
        } catch (EOFException | SocketException e) {
            // client closed quietly
            System.out.println("[ERROR] " + client.getRemoteSocketAddress() + " -> " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Client " + client.getRemoteSocketAddress() + " error: " + e.getMessage());
            try { sendCloseAndRemove(1011, "Internal error"); } catch (Exception ignore) {}
        } finally {
            close();
            System.out.println("[CLOSE] Connection closed for " + client.getRemoteSocketAddress());
        }
    }

    /* ----------------------------- Fragmentation ---------------------------- */

    private void startAssembly(int opcode) {
        System.out.println("[ASSEMBLY] Start assembly, type=" + (opcode==1?"TEXT":"BINARY"));
        assembling = true;
        assemblingType = opcode;
        buffer.reset();
    }

    private void appendAssembly(byte[] chunk) throws IOException {
        if ((buffer.size() + (long)chunk.length) > MAX_MESSAGE_BYTES) {
            throw new IOException("Assembled message too large");
        }
        buffer.write(chunk);
        System.out.println("[ASSEMBLY] Appended " + chunk.length + " bytes (total=" + buffer.size() + ")");
    }

    private byte[] endAssembly() {
        assembling = false;
//        int t = assemblingType;
        assemblingType = 0;
        byte[] data = buffer.toByteArray();
        buffer.reset();
        System.out.println("[ASSEMBLY] End assembly, total=" + data.length + " bytes");
        return data;
    }

    /* ----------------------------- Compression ----------------------------- */

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

    /* ----------------------------- Frame sending --------------------------- */

    public void sendText(String message) {
        try {
            System.out.println("[SEND] Sending text: " + message);
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            sendDataFrame((byte)0x1, payload, perMessageDeflate.get());
        } catch (IOException e) {
            System.out.println("[SEND-ERROR] Text send failed: " + e.getMessage());
        }
    }

    public void sendBinary(byte[] data) {
        try {
            System.out.println("[SEND] Sending binary " + data.length + " bytes");
            sendDataFrame((byte)0x2, data, perMessageDeflate.get());
        } catch (IOException e) {
            System.out.println("[SEND-ERROR] Binary send failed: " + e.getMessage());
        }
    }

    private void sendPong(byte[] payload) {
        try {
            sendControlFrame((byte)0xA, payload);
        } catch (IOException ignored) {}
    }

    private void sendControlFrame(byte opcode, byte[] payload) throws IOException {
        // Control frames must be <=125 and not masked (server->client)
        synchronized (out) {
            out.write(0x80 | (opcode & 0x0F));
            out.write(payload.length & 0x7F);
            if (payload.length > 0) out.write(payload);
            out.flush();
        }
    }

    private void sendDataFrame(byte opcode, byte[] payload, boolean compress) throws IOException {
        System.out.printf("[SEND] Sending frame: OPCODE=0x%X, PayloadLen=%d%n", opcode, payload.length);
        synchronized (out) {
            byte[] outPayload = payload;
            int rsv = 0;
            if (compress && outPayload.length > 0) {
                try {
                    outPayload = deflatePerMessage(outPayload);
                    rsv = 0x40; // RSV1 bit set
                } catch (IOException ex) {
                    // if compression fails, fall back to uncompressed send
                    outPayload = payload;
                    rsv = 0;
                }
            }
            out.write((byte)(0x80 | rsv | (opcode & 0x0F)));
            int len = outPayload.length;
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
            if (len > 0) out.write(outPayload);
            out.flush();
        }
    }

    private void sendCloseAndRemove(int code, String reason) throws IOException {
        sendClose(code, reason);
        close();
    }

    private void sendClose(int code, String reason) throws IOException {
        System.out.println("[CLOSE] Sending close frame code=" + code + ", reason=" + reason);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write((code >>> 8) & 0xFF);
        baos.write(code & 0xFF);
        if (reason != null && !reason.isEmpty()) {
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            baos.write(r, 0, Math.min(r.length, CTRL_MAX_LEN - 2));
        }
        byte[] payload = baos.toByteArray();
        synchronized (out) {
            out.write((byte)(0x80 | 0x8)); // FIN + opcode 8
            int len = payload.length;
            out.write(len & 0x7F);
            if (len > 0) out.write(payload);
            out.flush();
        }
    }

    /* ----------------------------- Close handling ------------------------- */

    private void handleClose(byte[] payload) throws IOException {
        int code = 1000; String reason = "";
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (payload.length > 2) {
                byte[] r = Arrays.copyOfRange(payload, 2, payload.length);
                if (!isValidUtf8(r)) {
                    sendClose(1007, "Invalid UTF-8 in close reason");
                } else {
                    reason = new String(r, StandardCharsets.UTF_8);
                }
            }
        }
        // Echo close with same code/reason
        sendClose(code, reason);
    }

    /* ----------------------------- IO helpers ----------------------------- */

    private int readByte() throws IOException {
        int b = is.read();
        if (b == -1) throw new EOFException();
        return b & 0xFF;
    }

    private byte[] readN(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r == -1) throw new EOFException();
            off += r;
        }
        return buf;
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

    /* ----------------------------- Utils -------------------------------- */

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

    private void close() {
        try { client.close(); } catch (IOException ignored) {}
        System.out.println("[CONNECTION] Closed " + client.getRemoteSocketAddress());
        WebSocketApplication.clients.remove(this);
    }
}