package com.stripedkv;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AofWriter {
    private final BlockingQueue<String> queue;
    private final Thread writerThread;
    private volatile boolean running;

    public AofWriter(String filePath) {
        this.queue = new LinkedBlockingQueue<>();
        this.running = true;
        this.writerThread = new Thread(() -> {
            try (FileOutputStream fos = new FileOutputStream(filePath, true)) {
                while (running || !queue.isEmpty()) {
                    // take() blocks indefinitely at the OS level until an item is available, 
                    // consuming 0% CPU while idle.
                    String command = queue.take(); 
                    if (command != null) {
                        fos.write(command.getBytes(StandardCharsets.UTF_8));
                        fos.flush(); // Flush to OS buffers
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (running) {
                    System.err.println("AofWriter error: " + e.getMessage());
                }
            }
        });
        this.writerThread.setName("AofWriter-Thread");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Appends a raw command string to the AOF.
     * @param command the raw command string ending with \r\n
     */
    public void append(String command) {
        if (!running) return;
        queue.offer(command);
    }

    public void stop() {
        this.running = false;
        this.writerThread.interrupt(); // Wake up if blocked on take()
        try {
            this.writerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
