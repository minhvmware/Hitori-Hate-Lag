package com.hitori.antilag.util;

/**
 * Long-keyed hash map for chunk coordinate lookups.
 * Uses packed (chunkX, chunkZ) as 64-bit key to avoid object allocation.
 * 
 * Chunk coordinates can be packed as: (long)chunkX << 32 | (chunkZ &
 * 0xFFFFFFFFL)
 */
public final class LongObjectMap<V> {

    private static final int DEFAULT_CAPACITY = 64;
    private static final float LOAD_FACTOR = 0.75f;
    private static final long EMPTY_KEY = Long.MIN_VALUE;

    private long[] keys;
    private Object[] values;
    private int size;
    private int threshold;

    public LongObjectMap() {
        this(DEFAULT_CAPACITY);
    }

    public LongObjectMap(int initialCapacity) {
        int capacity = nextPowerOfTwo(initialCapacity);
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.threshold = (int) (capacity * LOAD_FACTOR);

        java.util.Arrays.fill(keys, EMPTY_KEY);
    }

    /**
     * Pack chunk coordinates into a single long key
     */
    public static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Unpack chunkX from long key
     */
    public static int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    /**
     * Unpack chunkZ from long key
     */
    public static int unpackChunkZ(long key) {
        return (int) key;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        if (key == EMPTY_KEY)
            return null;

        int mask = keys.length - 1;
        int idx = (int) (key ^ (key >>> 32)) & mask;

        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                return (V) values[idx];
            }
            idx = (idx + 1) & mask;
        }

        return null;
    }

    /**
     * Get by chunk coordinates (allocation-free)
     */
    public V get(int chunkX, int chunkZ) {
        return get(packChunkKey(chunkX, chunkZ));
    }

    public V put(long key, V value) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("Key cannot be " + EMPTY_KEY);
        }

        if (size >= threshold) {
            resize();
        }

        return putInternal(key, value);
    }

    /**
     * Put by chunk coordinates (allocation-free)
     */
    public V put(int chunkX, int chunkZ, V value) {
        return put(packChunkKey(chunkX, chunkZ), value);
    }

    @SuppressWarnings("unchecked")
    private V putInternal(long key, V value) {
        int mask = keys.length - 1;
        int idx = (int) (key ^ (key >>> 32)) & mask;

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
    public V remove(long key) {
        if (key == EMPTY_KEY)
            return null;

        int mask = keys.length - 1;
        int idx = (int) (key ^ (key >>> 32)) & mask;

        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                V old = (V) values[idx];
                keys[idx] = EMPTY_KEY;
                values[idx] = null;
                size--;

                // Rehash cluster
                idx = (idx + 1) & mask;
                while (keys[idx] != EMPTY_KEY) {
                    long k = keys[idx];
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

    /**
     * Remove by chunk coordinates (allocation-free)
     */
    public V remove(int chunkX, int chunkZ) {
        return remove(packChunkKey(chunkX, chunkZ));
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
        long[] oldKeys = keys;
        Object[] oldValues = values;

        int newCapacity = keys.length << 1;
        keys = new long[newCapacity];
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
    public void forEach(LongObjectConsumer<V> consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY_KEY) {
                @SuppressWarnings("unchecked")
                V v = (V) values[i];
                consumer.accept(keys[i], v);
            }
        }
    }

    /**
     * Iterate with unpacked chunk coordinates
     */
    public void forEachChunk(ChunkConsumer<V> consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY_KEY) {
                @SuppressWarnings("unchecked")
                V v = (V) values[i];
                consumer.accept(unpackChunkX(keys[i]), unpackChunkZ(keys[i]), v);
            }
        }
    }

    @FunctionalInterface
    public interface LongObjectConsumer<V> {
        void accept(long key, V value);
    }

    @FunctionalInterface
    public interface ChunkConsumer<V> {
        void accept(int chunkX, int chunkZ, V value);
    }
}
