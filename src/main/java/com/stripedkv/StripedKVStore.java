package com.stripedkv;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class StripedKVStore implements KVStore {
    private static final int NUM_LOCKS = 16;
    private static final int NUM_BUCKETS = 1024;
    
    private final ReentrantLock[] locks;
    private final Node[] buckets;

    private static class Node {
        String key;
        String value;
        long expirationTimeMs;
        Node next;

        Node(String key, String value, Node next) {
            this.key = key;
            this.value = value;
            this.expirationTimeMs = -1; // -1 means no expiration
            this.next = next;
        }
    }

    private static class ExpirationEvent implements Comparable<ExpirationEvent> {
        final String key;
        final long expirationTimeMs;

        ExpirationEvent(String key, long expirationTimeMs) {
            this.key = key;
            this.expirationTimeMs = expirationTimeMs;
        }

        @Override
        public int compareTo(ExpirationEvent o) {
            return Long.compare(this.expirationTimeMs, o.expirationTimeMs);
        }
    }

    private final PriorityBlockingQueue<ExpirationEvent> evictionQueue;
    private final Thread evictorThread;

    public StripedKVStore() {
        locks = new ReentrantLock[NUM_LOCKS];
        for (int i = 0; i < NUM_LOCKS; i++) {
            locks[i] = new ReentrantLock();
        }
        buckets = new Node[NUM_BUCKETS];
        evictionQueue = new PriorityBlockingQueue<>();

        evictorThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ExpirationEvent event = evictionQueue.peek();
                    if (event == null) {
                        // Queue empty, wait for an element
                        event = evictionQueue.take();
                        evictionQueue.put(event); // put it back, we just wanted to block
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    long delay = event.expirationTimeMs - now;

                    if (delay <= 0) {
                        // Expired! Pop it and attempt active deletion
                        evictionQueue.poll();
                        activeDelete(event.key);
                    } else {
                        // Sleep until it expires
                        synchronized (evictionQueue) {
                            evictionQueue.wait(delay);
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Thread interrupted, exit gracefully
                Thread.currentThread().interrupt();
            }
        });
        evictorThread.setName("Evictor-Thread");
        evictorThread.setDaemon(true);
        evictorThread.start();
    }

    private int getLockIndex(String key) {
        return (key.hashCode() & 0x7fffffff) % NUM_LOCKS;
    }

    private int getBucketIndex(String key) {
        return (key.hashCode() & 0x7fffffff) % NUM_BUCKETS;
    }

    // Helper to wake up the evictor thread if a new earlier expiration is added
    private void notifyEvictor() {
        synchronized (evictionQueue) {
            evictionQueue.notify();
        }
    }

    private void activeDelete(String key) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node current = buckets[bucketIdx];
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
                        // Confirmed expired, delete it
                        if (prev == null) {
                            buckets[bucketIdx] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                    }
                    return;
                }
                prev = current;
                current = current.next;
            }
        } finally {
            locks[lockIdx].unlock();
        }
    }

    @Override
    public void set(String key, String value) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node head = buckets[bucketIdx];
            Node current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    current.value = value;
                    current.expirationTimeMs = -1; // Reset expiration on SET
                    return;
                }
                current = current.next;
            }
            buckets[bucketIdx] = new Node(key, value, head);
        } finally {
            locks[lockIdx].unlock();
        }
    }

    @Override
    public String get(String key) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node current = buckets[bucketIdx];
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    // Lazy Deletion Check
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
                        if (prev == null) {
                            buckets[bucketIdx] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                        return null; // Expired
                    }
                    return current.value;
                }
                prev = current;
                current = current.next;
            }
            return null;
        } finally {
            locks[lockIdx].unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node current = buckets[bucketIdx];
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    if (prev == null) {
                        buckets[bucketIdx] = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    return true;
                }
                prev = current;
                current = current.next;
            }
            return false;
        } finally {
            locks[lockIdx].unlock();
        }
    }

    @Override
    public int incr(String key) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node head = buckets[bucketIdx];
            Node current = head;
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    // Lazy Deletion Check before INCR
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
                        if (prev == null) {
                            buckets[bucketIdx] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                        break; // Deleted, will be recreated below
                    }
                    int val = Integer.parseInt(current.value);
                    val++;
                    current.value = String.valueOf(val);
                    return val;
                }
                prev = current;
                current = current.next;
            }
            // Key not found or expired, initialize to 1
            buckets[bucketIdx] = new Node(key, "1", head);
            return 1;
        } finally {
            locks[lockIdx].unlock();
        }
    }

    @Override
    public boolean expire(String key, int seconds) {
        int lockIdx = getLockIndex(key);
        int bucketIdx = getBucketIndex(key);
        locks[lockIdx].lock();
        try {
            Node current = buckets[bucketIdx];
            while (current != null) {
                if (current.key.equals(key)) {
                    // Update expiration
                    long expireTime = System.currentTimeMillis() + (seconds * 1000L);
                    current.expirationTimeMs = expireTime;
                    evictionQueue.offer(new ExpirationEvent(key, expireTime));
                    notifyEvictor();
                    return true;
                }
                current = current.next;
            }
            return false;
        } finally {
            locks[lockIdx].unlock();
        }
    }
}
