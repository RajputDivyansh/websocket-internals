package com.personal.websocket;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

//@SpringBootApplication
public class WebSocketApplicationSingleThreaded {

	public static void main(String[] args) throws Exception {
		int port = 8080;
		ServerSocket serverSocket = new ServerSocket(port);
		System.out.println("WebSocket server started on port " + port);

		while (true) {
			Socket client = serverSocket.accept();
			System.out.println("New client connected: " + client.getInetAddress());

			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
			OutputStream out = client.getOutputStream();

			// --- Step 1: Read HTTP Handshake Request ---
			String line;
			String webSocketKey = null;
			while (!(line = in.readLine()).isEmpty()) {
				if (line.startsWith("Sec-WebSocket-Key:")) {
					webSocketKey = line.split(":")[1].trim();
				}
			}

			// --- Step 2: Generate Sec-WebSocket-Accept ---
			String magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
			String acceptKey = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
				.digest((webSocketKey + magicString).getBytes("UTF-8")));

			// --- Step 3: Send Handshake Response ---
			String response = "HTTP/1.1 101 Switching Protocols\r\n" + "Connection: Upgrade\r\n"
				+ "Upgrade: websocket\r\n" + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
			out.write(response.getBytes());
			out.flush();
			System.out.println("Handshake done with client.");

			// --- Step 4: Read Frames + Echo Back ---
			InputStream is = client.getInputStream();
			while (true) {
				int b1 = is.read();
				int b2 = is.read();
				if (b1 == -1 || b2 == -1)
					break;

				int opcode = b1 & 0x0F;
				int payloadLen = b2 & 0x7F;

				byte[] mask = new byte[4];
				is.read(mask, 0, 4);

				System.out.println(Arrays.toString(mask));
				System.out.println(new String(mask));
				byte[] payload = new byte[payloadLen];
				is.read(payload, 0, payloadLen);
				System.out.println(Arrays.toString(payload));
				System.out.println(new String(payload));

				// Unmask payload
				for (int i = 0; i < payloadLen; i++) {
					payload[i] = (byte) (payload[i] ^ mask[i % 4]);
				}

				System.out.println(Arrays.toString(payload));
				System.out.println(new String(payload));

				String message = new String(payload, "UTF-8");
				System.out.println("Received: " + message);

				// Echo message back (simple text frame)
				byte[] responseBytes = message.getBytes("UTF-8");
				out.write(0x81); // FIN + text frame opcode
				out.write(responseBytes.length);
				out.write(responseBytes);
				out.flush();
			}

			client.close();
		}
	}
}