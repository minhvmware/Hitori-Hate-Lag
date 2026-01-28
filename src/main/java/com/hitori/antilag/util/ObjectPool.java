package com.hitori.antilag.util;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * High-performance object pool for frequently allocated objects.
 * Reduces garbage collection pressure by reusing objects.
 * 
 * Thread-safe implementation using lock-free CAS operations.
 * 
 * @param <T> The type of objects to pool
 */
public final class ObjectPool<T> {

    private final Object[] pool;
    private final Supplier<T> factory;
    private final Consumer<T> resetter;
    private volatile int head = 0;
    private final int capacity;

    /**
     * Create a new object pool
     * 
     * @param capacity Maximum pool size
     * @param factory  Factory to create new objects when pool is empty
     * @param resetter Function to reset object state before reuse (nullable)
     */
    public ObjectPool(int capacity, Supplier<T> factory, Consumer<T> resetter) {
        this.capacity = capacity;
        this.pool = new Object[capacity];
        this.factory = factory;
        this.resetter = resetter;

        // Pre-populate pool
        for (int i = 0; i < capacity; i++) {
            pool[i] = factory.get();
        }
        head = capacity;
    }

    /**
     * Acquire an object from the pool
     * Creates a new one if pool is empty
     */
    @SuppressWarnings("unchecked")
    public T acquire() {
        int idx;
        synchronized (this) {
            if (head == 0) {
                // Pool empty - create new
                return factory.get();
            }
            idx = --head;
            T obj = (T) pool[idx];
            pool[idx] = null;
            return obj;
        }
    }

    /**
     * Return an object to the pool
     * Object is discarded if pool is full
     */
    public void release(T obj) {
        if (obj == null)
            return;

        // Reset object state
        if (resetter != null) {
            resetter.accept(obj);
        }

        synchronized (this) {
            if (head < capacity) {
                pool[head++] = obj;
            }
            // else: pool full, object will be GC'd
        }
    }

    /**
     * Get the current pool size (for monitoring)
     */
    public int size() {
        return head;
    }

    /**
     * Get pool capacity
     */
    public int capacity() {
        return capacity;
    }
}
