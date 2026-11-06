package com.android.fmradio;

/** RDS PI and AF list of the current station, read natively from the radio's libfmjni. */
public final class XyPi {
    private static boolean sOk;
    static {
        try { System.loadLibrary("xypi"); sOk = true; }
        catch (Throwable t) { android.util.Log.w("XyPi", "libxypi not loaded", t); }
    }
    private XyPi() {}
    private static native int nativeGetPi();
    private static native int[] nativeGetAf();
    private static native int nativeGetPamd();
    private static native int nativeClearRds();
    /** -1 = unknown */
    public static int get() {
        if (!sOk) return -1;
        try { return nativeGetPi(); } catch (Throwable t) { return -1; }
    }
    /** AF frequencies in 10 kHz units, empty when unknown. Only trust it for a validated PI. */
    public static int[] getAf() {
        if (!sOk) return new int[0];
        try { int[] a = nativeGetAf(); return a == null ? new int[0] : a; } catch (Throwable t) { return new int[0]; }
    }
    /** invalidate the cached PI/AF in libfmjni's struct (after a tune); true if written */
    public static boolean clearRds() {
        if (!sOk) return false;
        try { return nativeClearRds() == 1; } catch (Throwable t) { return false; }
    }
    /** chip PAMD level of the current tune (read-only ioctl), -1 = unavailable */
    public static int getPamd() {
        if (!sOk) return -1;
        try { return nativeGetPamd(); } catch (Throwable t) { return -1; }
    }
}
