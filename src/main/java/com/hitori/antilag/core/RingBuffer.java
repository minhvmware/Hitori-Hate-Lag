package com.hitori.antilag.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe ring buffer for storing historical data
 */
public class RingBuffer<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;
    private final Object lock = new Object();

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /**
     * Add an element to the buffer (overwrites oldest if full)
     */
    public void add(T element) {
        synchronized (lock) {
            buffer[head] = element;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }
    }

    /**
     * Get the most recently added element
     */
    @SuppressWarnings("unchecked")
    public T getLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int lastIndex = (head - 1 + capacity) % capacity;
            return (T) buffer[lastIndex];
        }
    }

    /**
     * Get the last N elements (oldest to newest)
     */
    @SuppressWarnings("unchecked")
    public List<T> getLastN(int n) {
        synchronized (lock) {
            if (size == 0) {
                return Collections.emptyList();
            }

            int count = Math.min(n, size);
            List<T> result = new ArrayList<>(count);

            // Calculate starting position
            int start = (head - count + capacity) % capacity;

            for (int i = 0; i < count; i++) {
                int index = (start + i) % capacity;
                result.add((T) buffer[index]);
            }

            return result;
        }
    }

    /**
     * Get all elements (oldest to newest)
     */
    public List<T> getAll() {
        return getLastN(size);
    }

    /**
     * Get element at specific index (0 = oldest, size-1 = newest)
     */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }

            int actualIndex = (head - size + index + capacity) % capacity;
            return (T) buffer[actualIndex];
        }
    }

    /**
     * Get current number of elements
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Get maximum capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Check if buffer is empty
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Check if buffer is full
     */
    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    /**
     * Clear all elements
     */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            size = 0;
        }
    }
}
