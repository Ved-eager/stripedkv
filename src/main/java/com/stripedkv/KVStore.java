package com.stripedkv;

public interface KVStore {
    /**
     * Sets the string value of a key.
     * @param key the key
     * @param value the value
     */
    void set(String key, String value);

    /**
     * Get the value of a key.
     * @param key the key
     * @return the value, or null if the key does not exist
     */
    String get(String key);

    /**
     * Delete a key.
     * @param key the key
     * @return true if the key was removed, false if it did not exist
     */
    boolean delete(String key);

    /**
     * Increments the number stored at key by one.
     * If the key does not exist, it is set to 0 before performing the operation.
     * @param key the key
     * @return the value of key after the increment
     * @throws NumberFormatException if the value is not an integer
     */
    int incr(String key);

    /**
     * Sets a timeout on key. After the timeout has expired, the key will automatically be deleted.
     * @param key the key
     * @param seconds the time to live in seconds
     * @return true if the timeout was set, false if the key does not exist
     */
    boolean expire(String key, int seconds);
}
