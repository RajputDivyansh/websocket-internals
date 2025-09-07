package com.websocket.internal;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

// This test only works if server echoes it back to same client.

public class WebSocketConcurrencyBenchmark {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;
    private static final int CLIENTS = 50;
    private static final int MESSAGES_PER_CLIENT = 100;

    private enum Scenario { TEXT, BINARY }

    private static class Result {
        List<Long> latencies = new ArrayList<>();
        long totalLatency = 0;

        void record(long latency) {
            latencies.add(latency);
            totalLatency += latency;
        }

        double avgMs() { return latencies.isEmpty() ? 0 : (totalLatency / 1e6) / latencies.size(); }

        double throughput(double seconds) { return latencies.size() / seconds; }

        double percentile(double pct) {
            if (latencies.isEmpty()) return 0;
            Collections.sort(latencies);
            int idx = (int)Math.ceil((pct / 100.0) * latencies.size()) - 1;
            return latencies.get(Math.max(0, idx)) / 1e6; // ms
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Baseline Run (no compression) ===");
        runBenchmark(false);

        System.out.println("\n=== Compression Run (permessage-deflate) ===");
        runBenchmark(true);
    }

    private static void runBenchmark(boolean compression) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        List<Future<Map<Scenario, Result>>> futures = new ArrayList<>();

        long startTime = System.nanoTime();
        for (int i = 0; i < CLIENTS; i++) {
            futures.add(pool.submit(new ClientTask(i, compression)));
        }

        Map<Scenario, Result> aggregate = new EnumMap<>(Scenario.class);
        for (Scenario s : Scenario.values()) {
            aggregate.put(s, new Result());
        }

        for (Future<Map<Scenario, Result>> f : futures) {
            Map<Scenario, Result> res = f.get();
            for (Scenario s : Scenario.values()) {
                aggregate.get(s).latencies.addAll(res.get(s).latencies);
                aggregate.get(s).totalLatency += res.get(s).totalLatency;
            }
        }
        long endTime = System.nanoTime();
        double elapsed = (endTime - startTime) / 1e9;

        pool.shutdown();

        long totalMsgs = aggregate.values().stream().mapToInt(r -> r.latencies.size()).sum();
        System.out.println("\n--- Per Scenario ---");
        for (Scenario s : Scenario.values()) {
            Result r = aggregate.get(s);
            System.out.printf(
                  "%-12s: msgs=%d, throughput=%.2f msg/s, avg=%.3f ms, p50=%.3f ms, p90=%.3f ms, p99=%.3f ms%n",
                  s, r.latencies.size(), r.throughput(elapsed), r.avgMs(),
                  r.percentile(50), r.percentile(90), r.percentile(99)
            );
        }

        System.out.println("\n--- Overall ---");
        System.out.printf("Clients=%d, Total Msg=%d, Elapsed=%.2fs, Throughput=%.2f msg/s%n",
              CLIENTS, totalMsgs, elapsed, totalMsgs / elapsed);
    }

    private static class ClientTask implements Callable<Map<Scenario, Result>> {
        private final int id;
        private final boolean compression;

        ClientTask(int id, boolean compression) {
            this.id = id;
            this.compression = compression;
        }

        @Override
        public Map<Scenario, Result> call() throws Exception {
            Map<Scenario, Result> results = new EnumMap<>(Scenario.class);
            for (Scenario s : Scenario.values()) results.put(s, new Result());

            Socket socket = new Socket(HOST, PORT);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Handshake
            String wsKey = Base64.getEncoder().encodeToString(("key" + id).getBytes());
            StringBuilder req = new StringBuilder();
            req.append("GET / HTTP/1.1\r\n")
                  .append("Host: ").append(HOST).append(":").append(PORT).append("\r\n")
                  .append("Upgrade: websocket\r\n")
                  .append("Connection: Upgrade\r\n")
                  .append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                  .append("Sec-WebSocket-Version: 13\r\n");
            if (compression) {
                req.append("Sec-WebSocket-Extensions: permessage-deflate\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { /* ignore */ }

            Random rand = new Random();

            for (int i = 0; i < MESSAGES_PER_CLIENT; i++) {
                Scenario scenario = Scenario.values()[i % Scenario.values().length];
                long start = System.nanoTime();

                switch (scenario) {
                    case TEXT -> {
                        String msg = "Hello text from client " + id + " #" + i;
                        byte[] payload = msg.getBytes(StandardCharsets.UTF_8);
                        if (compression) payload = deflate(payload);

                        sendFrame(out, (byte) 0x1, payload, compression);
                        byte[] echoed = readFrame(in);
                        if (compression) echoed = inflate(echoed);

                        String echoedStr = new String(echoed, StandardCharsets.UTF_8);
//                        if (!msg.equals(echoedStr)) {
//                            System.err.println("[Client " + id + "] TEXT mismatch!");
//                        }
                    }
                    case BINARY -> {
                        byte[] payload = new byte[256];
                        rand.nextBytes(payload);
                        if (compression) payload = deflate(payload);

                        sendFrame(out, (byte) 0x2, payload, compression);
                        byte[] echoed = readFrame(in);
                        if (compression) echoed = inflate(echoed);

//                        if (!Arrays.equals(payload, compression ? deflate(echoed) : echoed)) {
//                            // Compare decompressed payload with original only when compressed
//                            System.err.println("[Client " + id + "] BINARY mismatch!");
//                        }
                    }
                    // Does not work in case of compression but work totally fines without compression

//                    case FRAGMENTED -> {
//                        String msg = "Fragmented msg from client " + id + " #" + i;
//                        byte[] full = msg.getBytes(StandardCharsets.UTF_8);
//                        int mid = full.length / 2;
//                        byte[] part1 = Arrays.copyOfRange(full, 0, mid);
//                        byte[] part2 = Arrays.copyOfRange(full, mid, full.length);
//
//                        sendFragment(out, (byte) 0x1, false, part1, compression);
//                        sendFragment(out, (byte) 0x0, true, part2, compression);
//
//                        byte[] echoed = readFrame(in);
//                        String echoedStr = new String(echoed, StandardCharsets.UTF_8);
//                        if (!msg.equals(echoedStr)) {
//                            System.err.println("[Client " + id + "] FRAGMENTED mismatch!");
//                        }
//                    }
                }

                long latency = System.nanoTime() - start;
                results.get(scenario).record(latency);
            }

            socket.close();
            return results;
        }

        private void sendFrame(OutputStream out, byte opcode, byte[] payload, boolean compressed) throws IOException {
            int b1 = 0x80 | opcode;
            if (compressed) b1 |= 0x40; // RSV1
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
                for (int i = 7; i >= 0; i--) out.write((length >>> (8*i)) & 0xFF);
            }

            byte[] mask = {1,2,3,4};
            out.write(mask);
            for (int i = 0; i < payload.length; i++) {
                out.write(payload[i] ^ mask[i % 4]);
            }
            out.flush();
        }

        private void sendFragment(OutputStream out, byte opcode, boolean fin, byte[] payload, boolean rsv1) throws IOException {
            int b1 = (fin ? 0x80 : 0x00) | opcode;
            if (rsv1) {
                b1 |= 0x40;
            }
            out.write(b1);

            out.write(0x80 | payload.length);
            byte[] mask = {5,6,7,8};
            out.write(mask);
            for (int i = 0; i < payload.length; i++) {
                out.write(payload[i] ^ mask[i % 4]);
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
            return in.readNBytes(len);
        }

        // --- Compression helpers ---
        private static byte[] deflate(byte[] data) throws IOException {
            Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
            def.setInput(data);
            def.finish();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            while (!def.finished()) {
                int r = def.deflate(tmp);
                baos.write(tmp, 0, r);
            }
            def.end();
            byte[] out = baos.toByteArray();
            // strip RFC tail
            if (out.length >= 4 &&
                  out[out.length-4]==0 && out[out.length-3]==0 &&
                  out[out.length-2]==(byte)0xFF && out[out.length-1]==(byte)0xFF) {
                return Arrays.copyOf(out, out.length-4);
            }
            return out;
        }

        private static byte[] inflate(byte[] compressed) throws IOException {
            Inflater inflater = new Inflater(true);
            inflater.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            try {
                while (!inflater.finished() && !inflater.needsInput()) {
                    int r = inflater.inflate(tmp);
                    if (r == 0) break;
                    baos.write(tmp, 0, r);
                }
            } catch (DataFormatException e) {
                throw new IOException("inflate error", e);
            } finally {
                inflater.end();
            }
            return baos.toByteArray();
        }
    }
}
