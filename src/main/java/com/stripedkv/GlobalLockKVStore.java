package com.stripedkv;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class GlobalLockKVStore implements KVStore {
    private static final int NUM_BUCKETS = 1024;
    
    private final ReentrantLock globalLock;
    private final Node[] buckets;

    private static class Node {
        String key;
        String value;
        long expirationTimeMs;
        Node next;

        Node(String key, String value, Node next) {
            this.key = key;
            this.value = value;
            this.expirationTimeMs = -1; 
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

    public GlobalLockKVStore() {
        globalLock = new ReentrantLock();
        buckets = new Node[NUM_BUCKETS];
        evictionQueue = new PriorityBlockingQueue<>();

        evictorThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ExpirationEvent event = evictionQueue.peek();
                    if (event == null) {
                        event = evictionQueue.take();
                        evictionQueue.put(event);
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    long delay = event.expirationTimeMs - now;

                    if (delay <= 0) {
                        evictionQueue.poll();
                        activeDelete(event.key);
                    } else {
                        synchronized (evictionQueue) {
                            evictionQueue.wait(delay);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        evictorThread.setName("Evictor-Thread");
        evictorThread.setDaemon(true);
        evictorThread.start();
    }

    private int getBucketIndex(String key) {
        return Math.abs(key.hashCode()) % NUM_BUCKETS;
    }

    private void notifyEvictor() {
        synchronized (evictionQueue) {
            evictionQueue.notify();
        }
    }

    private void activeDelete(String key) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
        try {
            Node current = buckets[bucketIdx];
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
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
            globalLock.unlock();
        }
    }

    @Override
    public void set(String key, String value) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
        try {
            Node head = buckets[bucketIdx];
            Node current = head;
            while (current != null) {
                if (current.key.equals(key)) {
                    current.value = value;
                    current.expirationTimeMs = -1;
                    return;
                }
                current = current.next;
            }
            buckets[bucketIdx] = new Node(key, value, head);
        } finally {
            globalLock.unlock();
        }
    }

    @Override
    public String get(String key) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
        try {
            Node current = buckets[bucketIdx];
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
                        if (prev == null) {
                            buckets[bucketIdx] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                        return null;
                    }
                    return current.value;
                }
                prev = current;
                current = current.next;
            }
            return null;
        } finally {
            globalLock.unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
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
            globalLock.unlock();
        }
    }

    @Override
    public int incr(String key) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
        try {
            Node head = buckets[bucketIdx];
            Node current = head;
            Node prev = null;
            while (current != null) {
                if (current.key.equals(key)) {
                    if (current.expirationTimeMs > 0 && current.expirationTimeMs <= System.currentTimeMillis()) {
                        if (prev == null) {
                            buckets[bucketIdx] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                        break; 
                    }
                    int val = Integer.parseInt(current.value);
                    val++;
                    current.value = String.valueOf(val);
                    return val;
                }
                prev = current;
                current = current.next;
            }
            buckets[bucketIdx] = new Node(key, "1", head);
            return 1;
        } finally {
            globalLock.unlock();
        }
    }

    @Override
    public boolean expire(String key, int seconds) {
        int bucketIdx = getBucketIndex(key);
        globalLock.lock();
        try {
            Node current = buckets[bucketIdx];
            while (current != null) {
                if (current.key.equals(key)) {
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
            globalLock.unlock();
        }
    }
}
