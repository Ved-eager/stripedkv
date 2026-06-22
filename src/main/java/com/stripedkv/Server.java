package com.stripedkv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private static final int PORT = 6379;
    private final KVStore store;
    private final ExecutorService threadPool;
    private final AofWriter aofWriter;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public Server(KVStore store, int numThreads, String aofPath) {
        this.store = store;
        this.threadPool = Executors.newFixedThreadPool(numThreads);
        
        // Recover from AOF
        recoverFromAof(aofPath);
        
        this.aofWriter = new AofWriter(aofPath);
    }

    private void recoverFromAof(String aofPath) {
        File file = new File(aofPath);
        if (!file.exists()) {
            return;
        }
        
        System.out.println("Starting Crash Recovery from " + aofPath + "...");
        // Use a dummy client handler just for the command parser
        // We pass null for aofWriter so we don't duplicate logs while recovering
        ClientHandler dummyHandler = new ClientHandler(null, store, null);
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    dummyHandler.handleCommand(line);
                } catch (ProtocolException | NumberFormatException e) {
                    // Truncated or invalid line (e.g. killed mid-write)
                    System.err.println("WARNING: Truncated AOF entry discarded. Error: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("WARNING: Failed to recover line: " + line + ". Error: " + e.getMessage());
                }
            }
            System.out.println("Crash Recovery complete.");
        } catch (IOException e) {
            System.err.println("Failed to read AOF file: " + e.getMessage());
        }
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(PORT);
        System.out.println("Server started on port " + PORT);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(new ClientHandler(clientSocket, store, aofWriter));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Accept failed: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }
        threadPool.shutdown();
        if (aofWriter != null) {
            aofWriter.stop();
        }
    }

    public static void main(String[] args) throws IOException {
        KVStore store;
        if (args.length > 0 && "global".equalsIgnoreCase(args[0])) {
            System.out.println("Starting server with GLOBAL LOCK");
            store = new GlobalLockKVStore();
        } else {
            System.out.println("Starting server with STRIPED LOCK");
            store = new StripedKVStore();
        }
        
        Server server = new Server(store, Runtime.getRuntime().availableProcessors() * 2, "aof.aof");
        
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        
        server.start();
    }
}
