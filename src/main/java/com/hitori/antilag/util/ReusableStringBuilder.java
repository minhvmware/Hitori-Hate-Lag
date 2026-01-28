package com.hitori.antilag.util;

/**
 * Allocation-free StringBuilder for hot-path string operations.
 * Reuses internal buffer across multiple uses.
 * 
 * Usage pattern:
 * 
 * <pre>
 * ReusableStringBuilder sb = new ReusableStringBuilder(128);
 * sb.reset().append("TPS: ").append(tps).append(" MSPT: ").append(mspt);
 * String result = sb.toString();
 * // sb can be reused without new allocation
 * </pre>
 */
public final class ReusableStringBuilder {

    private char[] buffer;
    private int length;

    // Pre-allocated char arrays for common number conversions
    private static final char[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };
    private static final int[] INT_SIZE_TABLE = {
            9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999, Integer.MAX_VALUE
    };

    // Thread-local scratch buffer for number formatting
    private final char[] numberBuffer = new char[20];

    public ReusableStringBuilder() {
        this(64);
    }

    public ReusableStringBuilder(int initialCapacity) {
        this.buffer = new char[initialCapacity];
        this.length = 0;
    }

    /**
     * Reset for reuse - no allocation
     */
    public ReusableStringBuilder reset() {
        length = 0;
        return this;
    }

    public ReusableStringBuilder append(String str) {
        if (str == null)
            str = "null";
        int len = str.length();
        ensureCapacity(length + len);
        str.getChars(0, len, buffer, length);
        length += len;
        return this;
    }

    public ReusableStringBuilder append(char c) {
        ensureCapacity(length + 1);
        buffer[length++] = c;
        return this;
    }

    public ReusableStringBuilder append(int value) {
        if (value == Integer.MIN_VALUE) {
            return append("-2147483648");
        }

        boolean negative = value < 0;
        if (negative) {
            ensureCapacity(length + 1);
            buffer[length++] = '-';
            value = -value;
        }

        int size = stringSize(value);
        ensureCapacity(length + size);

        // Write digits in reverse order
        int idx = length + size;
        while (value >= 10) {
            int q = value / 10;
            int r = value - (q * 10);
            buffer[--idx] = DIGITS[r];
            value = q;
        }
        buffer[--idx] = DIGITS[value];

        length += size;
        return this;
    }

    public ReusableStringBuilder append(long value) {
        if (value == Long.MIN_VALUE) {
            return append("-9223372036854775808");
        }

        boolean negative = value < 0;
        if (negative) {
            ensureCapacity(length + 1);
            buffer[length++] = '-';
            value = -value;
        }

        int size = stringSize(value);
        ensureCapacity(length + size);

        int idx = length + size;
        while (value >= 10) {
            long q = value / 10;
            int r = (int) (value - (q * 10));
            buffer[--idx] = DIGITS[r];
            value = q;
        }
        buffer[--idx] = DIGITS[(int) value];

        length += size;
        return this;
    }

    public ReusableStringBuilder append(double value, int decimals) {
        if (Double.isNaN(value)) {
            return append("NaN");
        }
        if (Double.isInfinite(value)) {
            return append(value > 0 ? "Infinity" : "-Infinity");
        }

        if (value < 0) {
            buffer[length++] = '-';
            value = -value;
        }

        long intPart = (long) value;
        append(intPart);

        if (decimals > 0) {
            buffer[length++] = '.';

            double fracPart = value - intPart;
            for (int i = 0; i < decimals; i++) {
                fracPart *= 10;
                int digit = (int) fracPart;
                buffer[length++] = DIGITS[digit];
                fracPart -= digit;
            }
        }

        return this;
    }

    public ReusableStringBuilder append(boolean value) {
        return append(value ? "true" : "false");
    }

    public ReusableStringBuilder append(Object obj) {
        return append(obj == null ? "null" : obj.toString());
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > buffer.length) {
            int newCapacity = Math.max(buffer.length << 1, minCapacity);
            char[] newBuffer = new char[newCapacity];
            System.arraycopy(buffer, 0, newBuffer, 0, length);
            buffer = newBuffer;
        }
    }

    private static int stringSize(int x) {
        for (int i = 0;; i++) {
            if (x <= INT_SIZE_TABLE[i]) {
                return i + 1;
            }
        }
    }

    private static int stringSize(long x) {
        long p = 10;
        for (int i = 1; i < 19; i++) {
            if (x < p)
                return i;
            p *= 10;
        }
        return 19;
    }

    public int length() {
        return length;
    }

    public char charAt(int index) {
        return buffer[index];
    }

    @Override
    public String toString() {
        return new String(buffer, 0, length);
    }

    /**
     * Write to existing char array without creating String
     */
    public int writeTo(char[] dest, int offset) {
        System.arraycopy(buffer, 0, dest, offset, length);
        return length;
    }
}
