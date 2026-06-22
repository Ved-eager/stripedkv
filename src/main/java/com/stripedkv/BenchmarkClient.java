package com.stripedkv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class BenchmarkClient {
    private static final int NUM_THREADS = 50;
    private static final int OPS_PER_THREAD = 2000;
    private static final int TOTAL_OPS = NUM_THREADS * OPS_PER_THREAD;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Benchmark Client...");
        System.out.println("Threads: " + NUM_THREADS);
        System.out.println("Total Operations: " + TOTAL_OPS);

        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(NUM_THREADS);
        AtomicLong totalLatencyNs = new AtomicLong(0);

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try (Socket socket = new Socket(HOST, PORT);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    
                    startLatch.await();

                    for (int j = 0; j < OPS_PER_THREAD; j++) {
                        String key = "key" + (threadId * OPS_PER_THREAD + j) % 10000;
                        String command;
                        if (j % 10 < 7) {
                            command = "GET " + key + "\r\n";
                        } else if (j % 10 < 9) {
                            command = "SET " + key + " val" + j + "\r\n";
                        } else {
                            command = "INCR " + key + "\r\n";
                        }

                        long startNs = System.nanoTime();
                        out.print(command);
                        out.flush();
                        in.readLine(); // wait for response
                        long endNs = System.nanoTime();
                        totalLatencyNs.addAndGet(endNs - startNs);
                    }
                } catch (Exception e) {
                    System.err.println("Thread " + threadId + " error: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        Thread.sleep(1000); // Warmup wait to let all sockets connect
        System.out.println("All threads connected. GO!");
        
        long startTimeMs = System.currentTimeMillis();
        startLatch.countDown();
        endLatch.await();
        long endTimeMs = System.currentTimeMillis();
        
        executor.shutdownNow();

        long totalTimeMs = endTimeMs - startTimeMs;
        double throughput = (TOTAL_OPS / (double) totalTimeMs) * 1000.0;
        double avgLatencyMs = (totalLatencyNs.get() / 1_000_000.0) / TOTAL_OPS;

        System.out.println("----------------------------------------");
        System.out.println("Benchmark Complete!");
        System.out.println("Total Time (ms)  : " + totalTimeMs);
        System.out.printf("Throughput (ops/s) : %.2f\n", throughput);
        System.out.printf("Avg Latency (ms)   : %.4f\n", avgLatencyMs);
        System.out.println("----------------------------------------");
    }
}
