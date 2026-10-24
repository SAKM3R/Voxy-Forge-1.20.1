package me.cortex.voxy.common.util;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Minimal stand-in for LWJGL's org.lwjgl.system.windows.Kernel32, which LWJGL 3.3.1 (the version
 * Minecraft 1.20.1 ships) does not have. Only used on Windows.
 */
public final class Kernel32 {
    private Kernel32() {}

    private static SharedLibrary LIBRARY;

    public static synchronized SharedLibrary getLibrary() {
        if (LIBRARY == null) {
            LIBRARY = APIUtil.apiCreateLibrary("kernel32");
        }
        return LIBRARY;
    }

    /**
     * GetCurrentThread() always returns the pseudo handle (HANDLE)-2.
     */
    public static long GetCurrentThread() {
        return -2L;
    }
}
