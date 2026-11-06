package com.android.fmradio;
/** Compile-time stub of the real (smali) FmService. Never packaged. */
public class FmService extends android.app.Service {
    public static int POWER_UP;
    public static int POWER_DOWN;
    public android.os.IBinder onBind(android.content.Intent i) { return null; }
    public int getFrequency() { return 0; }
    public int getPowerStatus() { return 0; }
    public String getPs() { return null; }
    public String getRtText() { return null; }
    public int getRssi() { return 0; }
    public int getRdsBler() { return 0; }
    public boolean isSeeking() { return false; }
    public boolean isScanning() { return false; }
    public boolean isMuted() { return false; }
    public int getRecorderState() { return 0; }
    public void tuneStationAsync(float frequency, int mStep, int mCountry) { }
}
