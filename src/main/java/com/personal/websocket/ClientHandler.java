package com.personal.websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;

public class ClientHandler implements Runnable {
    private Socket client;
    private InputStream is;
    private OutputStream out;

    public ClientHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            this.out = client.getOutputStream();

            // --- Handshake ---
            String line;
            String webSocketKey = null;
            while (!(line = in.readLine()).isEmpty()) {
                if (line.startsWith("Sec-WebSocket-Key:")) {
                    webSocketKey = line.split(":")[1].trim();
                }
            }

            String magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String acceptKey = Base64.getEncoder().encodeToString(
                  MessageDigest.getInstance("SHA-1")
                        .digest((webSocketKey + magicString).getBytes("UTF-8")));

            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                  + "Connection: Upgrade\r\n"
                  + "Upgrade: websocket\r\n"
                  + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes());
            out.flush();
            System.out.println("Handshake done with client: " + client.getInetAddress());

            // --- Frame Processing ---
            this.is = client.getInputStream();
            while (true) {
                int b1 = is.read();
                int b2 = is.read();
                if (b1 == -1 || b2 == -1)
                    break;

                boolean fin = (b1 & 0x80) != 0;
                int opcode = b1 & 0x0F;
                boolean masked = (b2 & 0x80) != 0;
                long payloadLen = b2 & 0x7F;

                if (!masked) {
                    throw new IOException("Client frames MUST be masked.");
                }

                // --- Extended Payload Length ---
                if (payloadLen == 126) {
                    payloadLen = ((is.read() & 0xFF) << 8) | (is.read() & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (is.read() & 0xFF);
                    }
                }

                // --- Mask Key ---
                byte[] mask = new byte[4];
                is.read(mask, 0, 4);

                // --- Payload ---
                byte[] payload = new byte[(int) payloadLen];
                is.read(payload, 0, (int) payloadLen);

                for (int i = 0; i < payloadLen; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }

                // --- Handle Frame ---
                switch (opcode) {
                    case 1: // Text
                        String message = new String(payload, "UTF-8");
                        System.out.println("Text from " + client.getInetAddress() + ": " + message);
                        WebSocketApplication.broadcast(message, this);
                        break;
                    case 2: // Binary
                        System.out.println("Binary frame (" + payload.length + " bytes)");
                        // for demo, echo binary back to sender
                        sendBinary(payload);
                        break;
                    case 8: // Close
                        System.out.println("Client closing connection...");
                        close();
                        return;
                    case 9: // Ping
                        sendPong(payload);
                        break;
                    case 10: // Pong
                        System.out.println("Pong received");
                        break;
                    default:
                        System.out.println("Unsupported opcode: " + opcode);
                }

                if (!fin) {
                    System.out.println("Fragmented frame detected (not fully implemented)");
                }
            }

            close();
        } catch (Exception e) {
            System.out.println("Client disconnected: " + e.getMessage());
            close();
        }
    }

    // --- Send Frames ---
    public void sendText(String message) {
        try {
            byte[] payload = message.getBytes("UTF-8");
            sendFrame((byte) 0x1, payload);
        } catch (IOException e) {
            System.out.println("Error sending text: " + e.getMessage());
        }
    }

    public void sendBinary(byte[] data) {
        try {
            sendFrame((byte) 0x2, data);
        } catch (IOException e) {
            System.out.println("Error sending binary: " + e.getMessage());
        }
    }

    public void sendPong(byte[] payload) {
        try {
            sendFrame((byte) 0xA, payload);
        } catch (IOException e) {
            System.out.println("Error sending pong: " + e.getMessage());
        }
    }

    private void sendFrame(byte opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode); // FIN=1, opcode
        if (payload.length <= 125) {
            out.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            out.write(126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (payload.length >>> (8 * i)) & 0xFF);
            }
        }
        out.write(payload);
        out.flush();
    }

    private void close() {
        try {
            client.close();
        } catch (IOException ignored) {}
        WebSocketApplication.clients.remove(this);
    }
}