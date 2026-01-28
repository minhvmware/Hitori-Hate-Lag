package com.hitori.antilag.util;

/**
 * Primitive int-to-Object map to avoid Integer boxing overhead.
 * Uses open addressing with linear probing.
 * 
 * This is significantly faster than HashMap<Integer, V> for hot paths
 * where we're doing many lookups per tick.
 */
public final class IntObjectMap<V> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int EMPTY_KEY = Integer.MIN_VALUE;

    private int[] keys;
    private Object[] values;
    private int size;
    private int threshold;

    public IntObjectMap() {
        this(DEFAULT_CAPACITY);
    }

    public IntObjectMap(int initialCapacity) {
        int capacity = nextPowerOfTwo(initialCapacity);
        this.keys = new int[capacity];
        this.values = new Object[capacity];
        this.threshold = (int) (capacity * LOAD_FACTOR);

        // Initialize keys to EMPTY
        java.util.Arrays.fill(keys, EMPTY_KEY);
    }

    @SuppressWarnings("unchecked")
    public V get(int key) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("Key cannot be " + EMPTY_KEY);
        }

        int mask = keys.length - 1;
        int idx = key & mask;

        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                return (V) values[idx];
            }
            idx = (idx + 1) & mask;
        }

        return null;
    }

    public V put(int key, V value) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("Key cannot be " + EMPTY_KEY);
        }

        if (size >= threshold) {
            resize();
        }

        return putInternal(key, value);
    }

    @SuppressWarnings("unchecked")
    private V putInternal(int key, V value) {
        int mask = keys.length - 1;
        int idx = key & mask;

        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                V old = (V) values[idx];
                values[idx] = value;
                return old;
            }
            idx = (idx + 1) & mask;
        }

        keys[idx] = key;
        values[idx] = value;
        size++;
        return null;
    }

    @SuppressWarnings("unchecked")
    public V remove(int key) {
        if (key == EMPTY_KEY)
            return null;

        int mask = keys.length - 1;
        int idx = key & mask;

        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                V old = (V) values[idx];
                keys[idx] = EMPTY_KEY;
                values[idx] = null;
                size--;

                // Rehash cluster
                idx = (idx + 1) & mask;
                while (keys[idx] != EMPTY_KEY) {
                    int k = keys[idx];
                    V v = (V) values[idx];
                    keys[idx] = EMPTY_KEY;
                    values[idx] = null;
                    size--;
                    putInternal(k, v);
                    idx = (idx + 1) & mask;
                }

                return old;
            }
            idx = (idx + 1) & mask;
        }

        return null;
    }

    public boolean containsKey(int key) {
        return get(key) != null;
    }

    public int size() {
        return size;
    }

    public void clear() {
        java.util.Arrays.fill(keys, EMPTY_KEY);
        java.util.Arrays.fill(values, null);
        size = 0;
    }

    private void resize() {
        int[] oldKeys = keys;
        Object[] oldValues = values;

        int newCapacity = keys.length << 1;
        keys = new int[newCapacity];
        values = new Object[newCapacity];
        threshold = (int) (newCapacity * LOAD_FACTOR);

        java.util.Arrays.fill(keys, EMPTY_KEY);

        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY_KEY) {
                @SuppressWarnings("unchecked")
                V v = (V) oldValues[i];
                putInternal(oldKeys[i], v);
            }
        }
    }

    private static int nextPowerOfTwo(int value) {
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        return value + 1;
    }

    /**
     * Iterate over entries without creating Iterator objects
     */
    public void forEach(IntObjectConsumer<V> consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY_KEY) {
                @SuppressWarnings("unchecked")
                V v = (V) values[i];
                consumer.accept(keys[i], v);
            }
        }
    }

    @FunctionalInterface
    public interface IntObjectConsumer<V> {
        void accept(int key, V value);
    }
}
