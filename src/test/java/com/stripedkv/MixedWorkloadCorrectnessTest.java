package com.stripedkv;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MixedWorkloadCorrectnessTest {

    public static void main(String[] args) throws InterruptedException {
        int numThreads = 20;
        int operationsPerThread = 1000;
        
        // Keys 0-4 are used for INCR/GET to verify exact mathematical consistency.
        String[] incrKeys = {"incrKey0", "incrKey1", "incrKey2", "incrKey3", "incrKey4"};
        // Keys 5-9 are used for SET/DELETE/GET to verify no deadlocks/exceptions.
        String[] otherKeys = {"otherKey0", "otherKey1", "otherKey2", "otherKey3", "otherKey4"};
        
        KVStore store = new StripedKVStore();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        
        AtomicInteger[] localIncrCounts = new AtomicInteger[incrKeys.length];
        for (int i = 0; i < incrKeys.length; i++) {
            localIncrCounts[i] = new AtomicInteger(0);
        }

        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // wait until all threads are ready
                    Random rand = new Random();
                    for (int j = 0; j < operationsPerThread; j++) {
                        int op = rand.nextInt(100);
                        
                        if (op < 50) {
                            // INCR/GET on incrKeys
                            int keyIdx = rand.nextInt(incrKeys.length);
                            String key = incrKeys[keyIdx];
                            if (rand.nextBoolean()) {
                                store.incr(key);
                                localIncrCounts[keyIdx].incrementAndGet();
                            } else {
                                store.get(key);
                            }
                        } else {
                            // SET/GET/DELETE on otherKeys
                            int keyIdx = rand.nextInt(otherKeys.length);
                            String key = otherKeys[keyIdx];
                            int subOp = rand.nextInt(3);
                            if (subOp == 0) {
                                store.set(key, "value" + j);
                            } else if (subOp == 1) {
                                store.get(key);
                            } else {
                                store.delete(key);
                            }
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        System.out.println("Starting 20 threads for Crucible Test...");
        startLatch.countDown();
        
        // Wait for all threads to finish (timeout to detect deadlocks)
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
        if (!completed) {
            throw new RuntimeException("Deadlock detected: threads did not complete in time.");
        }
        executor.shutdownNow();

        // 1. Zero Exceptions
        if (exceptionCount.get() != 0) {
            throw new RuntimeException("Test Failed! Expected 0 exceptions, got: " + exceptionCount.get());
        }

        // 2. Mathematically Consistent
        for (int i = 0; i < incrKeys.length; i++) {
            String val = store.get(incrKeys[i]);
            int expected = localIncrCounts[i].get();
            if (expected > 0) {
                if (val == null) {
                    throw new RuntimeException("Test Failed! Expected key " + incrKeys[i] + " to exist.");
                }
                int actual = Integer.parseInt(val);
                if (expected != actual) {
                    throw new RuntimeException("Test Failed! INCR count mismatch for " + incrKeys[i] + ". Expected " + expected + ", got " + actual);
                }
            } else {
                if (val != null) {
                    throw new RuntimeException("Test Failed! Key " + incrKeys[i] + " should not exist if never incremented.");
                }
            }
        }
        
        System.out.println("Crucible Test PASSED: Zero deadlocks, zero exceptions, state is mathematically consistent!");
    }
}
