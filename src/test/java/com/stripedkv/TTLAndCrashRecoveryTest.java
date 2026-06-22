package com.stripedkv;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TTLAndCrashRecoveryTest {

    public static void main(String[] args) throws Exception {
        System.out.println("--- Running TTL and Crash Recovery Tests ---");
        
        testTTL();
        testCrashRecovery();
        
        System.out.println("--- All Phase 3 & 4 Tests PASSED! ---");
    }

    private static void testTTL() throws Exception {
        System.out.println("Testing TTL and Eviction...");
        KVStore store = new StripedKVStore();
        
        store.set("ttlKey", "ttlValue");
        store.expire("ttlKey", 2);
        
        // Wait 1 second - should still exist
        Thread.sleep(1000);
        String val1 = store.get("ttlKey");
        if (val1 == null) {
            throw new RuntimeException("Test Failed! ttlKey should still exist after 1s");
        }
        
        // Wait 2 more seconds (3 total) - should be evicted
        Thread.sleep(2000);
        String val2 = store.get("ttlKey");
        if (val2 != null) {
            throw new RuntimeException("Test Failed! ttlKey should be null after 3s, got: " + val2);
        }
        
        System.out.println("TTL Test Passed.");
    }

    private static void testCrashRecovery() throws Exception {
        System.out.println("Testing Crash Recovery with Truncated AOF...");
        
        String aofFile = "test_aof.aof";
        File file = new File(aofFile);
        if (file.exists()) {
            file.delete();
        }
        
        // Programmatically create an AOF file with a corrupted last line
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("SET key1 val1\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("SET key2 val2\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("DELETE key2\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("INCR count1\r\n".getBytes(StandardCharsets.UTF_8));
            fos.write("SET key3 val3\r\n".getBytes(StandardCharsets.UTF_8));
            // Simulate kill -9 mid-write (Truncated line)
            fos.write("SET key4".getBytes(StandardCharsets.UTF_8)); 
        }
        
        // Initialize server which should automatically run recovery on test_aof.aof
        KVStore store = new StripedKVStore();
        Server server = new Server(store, 1, aofFile);
        
        // Assert the recovered state
        String k1 = store.get("key1");
        if (!"val1".equals(k1)) throw new RuntimeException("Expected key1=val1, got: " + k1);
        
        String k2 = store.get("key2");
        if (k2 != null) throw new RuntimeException("Expected key2 to be deleted");
        
        String c1 = store.get("count1");
        if (!"1".equals(c1)) throw new RuntimeException("Expected count1=1, got: " + c1);
        
        String k3 = store.get("key3");
        if (!"val3".equals(k3)) throw new RuntimeException("Expected key3=val3, got: " + k3);
        
        // Truncated line should be ignored, so key4 is null
        String k4 = store.get("key4");
        if (k4 != null) throw new RuntimeException("Expected key4 to be null because it was truncated");
        
        // Clean up
        server.stop();
        if (file.exists()) {
            file.delete();
        }
        System.out.println("Crash Recovery Test Passed.");
    }
}
