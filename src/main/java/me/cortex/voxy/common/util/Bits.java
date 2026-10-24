package me.cortex.voxy.common.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Java 19+ bit-scatter/gather ({@code Integer/Long.expand/compress}) and Java 21 {@code Math.clamp} for a
 * Java 17 class-file target.
 *
 * The mod is compiled for Java 17 (Forge 1.20.1 ships Mixin 0.8.5), but on a Java 21 runtime the real
 * JDK methods are used: they are bound once into static final MethodHandles, which the JIT constant-folds
 * and inlines, so the PDEP/PEXT intrinsics still apply. On Java 17 a portable implementation is bound instead.
 */
public final class Bits {
    private Bits() {}

    private static final MethodHandle INT_EXPAND = bind(Integer.class, "expand", int.class, "expandSw");
    private static final MethodHandle INT_COMPRESS = bind(Integer.class, "compress", int.class, "compressSw");
    private static final MethodHandle LONG_EXPAND = bind(Long.class, "expand", long.class, "expandSw");
    private static final MethodHandle LONG_COMPRESS = bind(Long.class, "compress", long.class, "compressSw");

    private static MethodHandle bind(Class<?> owner, String name, Class<?> type, String fallback) {
        var lookup = MethodHandles.lookup();
        var mt = MethodType.methodType(type, type, type);
        try {
            return lookup.findStatic(owner, name, mt);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            try {
                return lookup.findStatic(Bits.class, fallback, mt);
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }
    }

    public static int expand(int i, int mask) {
        try {
            return (int) INT_EXPAND.invokeExact(i, mask);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static int compress(int i, int mask) {
        try {
            return (int) INT_COMPRESS.invokeExact(i, mask);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static long expand(long i, long mask) {
        try {
            return (long) LONG_EXPAND.invokeExact(i, mask);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static long compress(long i, long mask) {
        try {
            return (long) LONG_COMPRESS.invokeExact(i, mask);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static int expandSw(int i, int mask) {
        int r = 0;
        for (int bb = 1; mask != 0; bb <<= 1) {
            int lowest = mask & -mask;
            if ((i & bb) != 0) r |= lowest;
            mask &= mask - 1;
        }
        return r;
    }

    private static int compressSw(int i, int mask) {
        int r = 0;
        for (int bb = 1; mask != 0; bb <<= 1) {
            if ((i & mask & -mask) != 0) r |= bb;
            mask &= mask - 1;
        }
        return r;
    }

    private static long expandSw(long i, long mask) {
        long r = 0;
        for (long bb = 1; mask != 0; bb <<= 1) {
            long lowest = mask & -mask;
            if ((i & bb) != 0) r |= lowest;
            mask &= mask - 1;
        }
        return r;
    }

    private static long compressSw(long i, long mask) {
        long r = 0;
        for (long bb = 1; mask != 0; bb <<= 1) {
            if ((i & mask & -mask) != 0) r |= bb;
            mask &= mask - 1;
        }
        return r;
    }

    public static int clamp(long value, int min, int max) {
        if (min > max) throw new IllegalArgumentException(min + " > " + max);
        return (int) Math.min(max, Math.max(value, min));
    }

    public static float clamp(float value, float min, float max) {
        if (min > max) throw new IllegalArgumentException(min + " > " + max);
        return Math.min(max, Math.max(value, min));
    }

    public static double clamp(double value, double min, double max) {
        if (min > max) throw new IllegalArgumentException(min + " > " + max);
        return Math.min(max, Math.max(value, min));
    }
}
