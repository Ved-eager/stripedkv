package com.stripedkv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final KVStore store;
    private final AofWriter aofWriter;

    public ClientHandler(Socket clientSocket, KVStore store, AofWriter aofWriter) {
        this.clientSocket = clientSocket;
        this.store = store;
        this.aofWriter = aofWriter;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                try {
                    String response = handleCommand(line);
                    out.print(response + "\r\n");
                    out.flush();
                } catch (ProtocolException e) {
                    out.print("ERROR " + e.getMessage() + "\r\n");
                    out.flush();
                } catch (NumberFormatException e) {
                    out.print("ERROR value is not an integer\r\n");
                    out.flush();
                } catch (Exception e) {
                    out.print("ERROR internal server error\r\n");
                    out.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("Client handler IO exception: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // Handles the command, and if it mutates state successfully, appends it to AOF
    public String handleCommand(String line) throws ProtocolException {
        String[] parts = line.trim().split("\\s+", 3);
        if (parts.length == 0) {
            throw new ProtocolException("empty command");
        }

        String command = parts[0].toUpperCase();
        
        switch (command) {
            case "SET":
                if (parts.length < 3) {
                    throw new ProtocolException("SET requires key and value");
                }
                store.set(parts[1], parts[2]);
                if (aofWriter != null) aofWriter.append(line + "\r\n");
                return "OK";
                
            case "GET":
                if (parts.length < 2) {
                    throw new ProtocolException("GET requires key");
                }
                String val = store.get(parts[1]);
                return val != null ? val : "NOT_FOUND";
                
            case "DELETE":
                if (parts.length < 2) {
                    throw new ProtocolException("DELETE requires key");
                }
                boolean deleted = store.delete(parts[1]);
                if (deleted && aofWriter != null) {
                    aofWriter.append(line + "\r\n");
                }
                return deleted ? "OK" : "NOT_FOUND";
                
            case "INCR":
                if (parts.length < 2) {
                    throw new ProtocolException("INCR requires key");
                }
                int newVal = store.incr(parts[1]);
                if (aofWriter != null) aofWriter.append(line + "\r\n");
                return String.valueOf(newVal);

            case "EXPIRE":
                if (parts.length < 3) {
                    throw new ProtocolException("EXPIRE requires key and seconds");
                }
                int seconds;
                try {
                    seconds = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    throw new ProtocolException("seconds must be an integer");
                }
                boolean expired = store.expire(parts[1], seconds);
                if (expired && aofWriter != null) {
                    aofWriter.append(line + "\r\n");
                }
                return expired ? "1" : "0"; // Redis convention: 1 if timeout was set, 0 if key does not exist
                
            default:
                throw new ProtocolException("unknown command: " + command);
        }
    }
}
