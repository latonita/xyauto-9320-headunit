package com.android.fmradio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * Publishes the FM radio as a standard MediaSession. Hooked into FmService (smali) by three
 * one-line calls: start() in onCreate, refresh() in notifyActivityStateChanged, stop() in onDestroy.
 * Transport commands are mapped to the same broadcasts the MCU / steering wheel already send to
 * the radio's own receiver (FmMainActivity), so no new tuning logic exists here.
 */
public final class XyMediaSession {
    private static final String TAG = "XyMediaSession";
    private static final long POLL_MS = 2000;
    private static final long FAST_POLL_MS = 500;
    private static final int FLAG_REGISTERED_ONLY = 0x40000000;
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;

    private static XyMediaSession sInstance;

    private final FmService mSvc;
    private final MediaSession mSession;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mLastKey;
    private boolean mEverPlayed;
    private int mLastPi = -2;
    private String mLastAf = "";
    private volatile int[] mAf = new int[0];
    private volatile int[] mAfKnown = new int[0];
    private String mLastKnown = "";
    private long mLastSigAt = -100000;
    private final File mStoreFile;
    private final XyAfStore mStore = new XyAfStore(new XyAfStore.Log() {
        @Override public void i(String s) { Log.i("XyAfStore", s); }
    });
    private long mSessionId = System.currentTimeMillis();     // one continuous stay on a frequency
    private int[] mHeldAf = new int[0]; private int mHeldAfFreq = -1;
    private int mCurPamd = -1;
    private int mFreqNow = -1, mLastGoodFreq = -1;
    private final SharedPreferences mPrefs;
    private final XyAfSwitch mSw;
    private final BroadcastReceiver mSwRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean on = i.getBooleanExtra("on", true);
            mPrefs.edit().putBoolean("afsw", on).apply();
            mSw.setEnabled(on);
            mMain.post(mUpdate);
        }
    };
    private final XyAfSwitch.Radio mRadio = new XyAfSwitch.Radio() {
        @Override public long now() { return SystemClock.elapsedRealtime(); }
        @Override public boolean powered() { return mSvc.getPowerStatus() == FmService.POWER_UP; }
        @Override public boolean busy() {
            int rec = -1;
            try { rec = mSvc.getRecorderState(); } catch (Throwable ignored) { }
            return mSvc.isSeeking() || mSvc.isScanning() || rec == 6 /* FmRecorder.STATE_RECORDING */;
        }
        @Override public int frequency() { return mFreqNow; }
        @Override public int pamd() { return mCurPamd; }
        @Override public int validatedPi() { return mPi.published(); }
        @Override public void tune(int f) {
            // Same call FmService$2 makes for the stock AF result: (frequency in MHz, mStep 0, mCountry 0). The handler
            // runs tuneStation(): mCurrentStation, FmStation.setCurrentStation (DB), notification and the activity callback.
            mSvc.tuneStationAsync(f / 100f, 0, 0);
        }
        @Override public int[] candidates(String pi, int exclude, int max) { return mStore.candidates(pi, exclude, max); }
        @Override public void recordSuccess(String pi, int f) {
            mStore.setLastSuccess(pi, f, System.currentTimeMillis());
            mStore.flush(SystemClock.elapsedRealtime(), mStoreFile, true);
        }
        @Override public void log(String x) { Log.i("XyAfSw", x); }
    };
    private final BroadcastReceiver mDumpRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dumpStore("dump"); }
    };
    private final XyPiTracker mPi = new XyPiTracker(new XyPiTracker.Log() {
        @Override public void i(String s) { Log.i("XyPi", s); }
    });

    private final Runnable mUpdate = new Runnable() {
        @Override public void run() {
            try { update(); } catch (Throwable t) { Log.w(TAG, "update failed", t); }
        }
    };
    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            mUpdate.run();
            mMain.postDelayed(this, mPi.wantFast(SystemClock.elapsedRealtime()) || mSw.probing() ? FAST_POLL_MS : POLL_MS);
        }
    };

    public static void start(FmService svc) {
        try {
            if (sInstance == null) sInstance = new XyMediaSession(svc);
        } catch (Throwable t) { Log.w(TAG, "start failed", t); }
    }

    /** AF list (10 kHz units) of the validated PI; empty if none */
    public static int[] publishedAf() {
        XyMediaSession s = sInstance;
        return s == null ? new int[0] : s.mAf;
    }

    /** confirmed AFs (store, hits >= 3) of the validated PI, 10 kHz units, sorted; empty if none */
    public static int[] publishedAfKnown() {
        XyMediaSession s = sInstance;
        return s == null ? new int[0] : s.mAfKnown;
    }

    /** validated PI for the v1 broadcast; -1 if none */
    public static int publishedPi() {
        XyMediaSession s = sInstance;
        return s == null ? -1 : s.mPi.published();
    }

    public static void refresh(FmService svc) {
        XyMediaSession s = sInstance;
        if (s != null) s.mMain.post(s.mUpdate);
    }

    public static void stop() {
        XyMediaSession s = sInstance;
        sInstance = null;
        if (s == null) return;
        try {
            s.mMain.removeCallbacksAndMessages(null);
            try { s.mSvc.unregisterReceiver(s.mDumpRx); } catch (Throwable ignored) { }
            try { s.mSvc.unregisterReceiver(s.mSwRx); } catch (Throwable ignored) { }
            s.mStore.flush(SystemClock.elapsedRealtime(), s.mStoreFile, true);
            s.mSession.setActive(false);
            s.mSession.release();
        } catch (Throwable t) { Log.w(TAG, "stop failed", t); }
    }

    private XyMediaSession(FmService svc) {
        mSvc = svc;
        mSession = new MediaSession(svc, "FmRadio");
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("xy.android.forceplay"); }
            @Override public void onPause() { send("xy.android.forcepause"); }
            @Override public void onStop() { send("xy.android.forcepause"); }
            @Override public void onSkipToNext() { send("xy.android.nextmedia"); }
            @Override public void onSkipToPrevious() { send("xy.android.previousmedia"); }
        });
        setState(PlaybackState.STATE_PAUSED);
        mPrefs = svc.getSharedPreferences("xy_fm_patch", Context.MODE_PRIVATE);
        mSw = new XyAfSwitch(mRadio);
        mSw.setEnabled(mPrefs.getBoolean("afsw", true));
        mStoreFile = new File(svc.getFilesDir(), "xyaf.txt");
        mStore.load(mStoreFile);
        dumpStore("start");
        try {
            IntentFilter f = new IntentFilter("xy.radio.afdump");
            svc.registerReceiver(mDumpRx, f);      // runtime-registered, so `am broadcast -a xy.radio.afdump` reaches it
        } catch (Throwable t) { Log.w(TAG, "afdump receiver failed", t); }
        try {
            svc.registerReceiver(mSwRx, new IntentFilter("xy.radio.afswitch"));   // am broadcast -a xy.radio.afswitch --ez on false
        } catch (Throwable t) { Log.w(TAG, "afswitch receiver failed", t); }
        mMain.post(mPoll);
    }

    private void dumpStore(String why) {
        List<String> lines = mStore.dumpLines();
        Log.i("XyAfStore", why + ": " + lines.size() + " PI(s) in " + mStoreFile);
        for (String l : lines) Log.i("XyAfStore", l);
    }

    private void send(String action) {
        Intent i = new Intent(action);
        i.addFlags(FLAG_REGISTERED_ONLY);
        mSvc.sendBroadcast(i);
    }

    private void setState(int state) {
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        state == PlaybackState.STATE_PLAYING ? 1f : 0f, SystemClock.elapsedRealtime());
        mSession.setPlaybackState(b.build());
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** PS the live RDS thread has seen, else the stored station record (what the radio's own UI shows). */
    private String storedPs(int freq) {
        Cursor c = null;
        try {
            c = mSvc.getContentResolver().query(FmStation.Station.CONTENT_URI,
                    new String[] {"program_service"}, "frequency=?",
                    new String[] {String.valueOf(freq)}, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable t) {
            Log.w(TAG, "db ps failed", t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void update() {
        int power = mSvc.getPowerStatus();
        boolean powered = power == FmService.POWER_UP;
        boolean playing = powered && !mSvc.isMuted();
        int freq = XyPiTracker.normFreq(mSvc.getFrequency(), mLastGoodFreq);
        if (freq >= 8750) mLastGoodFreq = freq;
        mFreqNow = freq;
        String ps = mSvc.getPs();
        String rt = mSvc.getRtText();
        long now = SystemClock.elapsedRealtime();
        int raw = powered ? XyPi.get() : -1;
        boolean tuned = mPi.onFreq(now, powered ? freq : -1);
        if (tuned) mSessionId = System.currentTimeMillis();
        boolean idle = powered && !mSvc.isSeeking() && !mSvc.isScanning();
        int bler = -1;
        if (idle) { try { bler = mSvc.getRdsBler(); } catch (Throwable ignored) { } }
        int pamd = powered ? XyPi.getPamd() : -1;
        mCurPamd = pamd;
        if (powered) mPi.onRead(now, freq, raw, bler, pamd);
        int pi = mPi.published();
        int[] rawAf = powered ? XyPi.getAf() : new int[0];
        mPi.noteRawAf(rawAf);
        // libfmjni keeps the previous station's PI/AF after a tune: invalidate its cache right after every detected tune
        // (user, our probe, tune-back). If it then reads back empty, anything that appears later is fresh.
        if (tuned && powered) {
            boolean cleared = XyPi.clearRds();
            if (cleared) mPi.noteCleared(now);
            Log.i("XyPi", "freq=" + freq + " libfmjni RDS cache cleared=" + cleared);
        }
        // Only fresh, clean AF data is used: not within 2.5 s of a tune, not at bler >= 20, not at a dead signal,
        // and not an unchanged leftover of the previous station's list.
        boolean afFresh = pi >= 0 && mPi.afFresh(now, rawAf, bler, pamd);
        int[] af;
        if (afFresh) { af = rawAf; mHeldAf = af; mHeldAfFreq = freq; }
        else af = (pi >= 0 && freq == mHeldAfFreq) ? mHeldAf : new int[0];        // hold the last good list on this frequency
        // Signal calibration log + AF store, once per 2 s while powered and not seeking/scanning (never toggles RDS).
        int[] known = mAfKnown;
        if (idle) {
            boolean tick = now - mLastSigAt >= POLL_MS - 100;
            if (tick) {
                mLastSigAt = now;
                int rssi = -1;
                try { rssi = mSvc.getRssi(); } catch (Throwable ignored) { }
                Log.i("XySig", "freq=" + freq + " pi=" + XyPiTracker.hex(pi) + " rssi=" + rssi
                        + " pamd=" + pamd + " bler=" + bler);
                if (pi >= 0) mStore.observe(System.currentTimeMillis(), XyPiTracker.hex(pi), freq, afFresh ? rawAf : new int[0], rssi, bler, mSessionId);
            }
        }
        mSw.tick();
        if (pi >= 0) known = mStore.confirmed(XyPiTracker.hex(pi)); else known = new int[0];
        mStore.flush(now, mStoreFile, false);
        StringBuilder kb = new StringBuilder();
        for (int f : known) { if (kb.length() > 0) kb.append(','); kb.append(f); }
        String knownStr = kb.toString();
        StringBuilder afb = new StringBuilder();
        for (int f : af) { if (afb.length() > 0) afb.append(','); afb.append(f); }
        String afStr = afb.toString();
        if (mSw.probing()) return;            // freeze what we publish while an AF probe is hopping frequencies
        String key = (mSw.enabled() ? "1" : "0") + "|" + power + "|" + playing + "|" + freq + "|" + ps + "|" + rt + "|" + pi + "|" + afStr + "|" + knownStr;
        if (key.equals(mLastKey)) return;
        mLastKey = key;

        mAf = af;
        mAfKnown = known;
        boolean knownChanged = !knownStr.equals(mLastKnown);
        mLastKnown = knownStr;
        boolean afChanged = !afStr.equals(mLastAf);
        if (afChanged) {
            mLastAf = afStr;
            Log.i("XyAf", "freq=" + freq + " pi=" + XyPiTracker.hex(pi) + " af=[" + afStr + "]");
        }
        if (pi != mLastPi || afChanged || knownChanged) {
            boolean piChanged = pi != mLastPi;
            if (piChanged)
            Log.i("XyPi", "freq=" + freq + " pi=" + (pi < 0 ? "none" : String.format(java.util.Locale.US, "%04X", pi)) + " (published)");
            mLastPi = pi;
            Intent r = new Intent("xy.radio.rds");
            r.putExtra("freq", freq).putExtra("ps", ps).putExtra("rt", rt).putExtra("pi", pi).putExtra("af", af).putExtra("afKnown", known);
            mSvc.sendBroadcast(r);
        }
        if (blank(ps)) ps = storedPs(freq);
        boolean fm = freq >= 6500;               // FM is in 10 kHz units (8820); AM is in kHz
        String fstr = fm ? String.format(java.util.Locale.US, "%.1f MHz", freq / 100f) : freq + " kHz";
        String title = blank(ps) ? fstr : ps.trim();
        String artist = blank(rt) ? null : rt.trim();
        String album = fm ? "FM" : "AM";

        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "fm:" + freq)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist != null ? artist : fstr);
        // PI stays out of MEDIA_ID: Dashwheel's parseFmMediaId needs "fm:<freq>" exactly.
        if (af.length > 0) m.putString("xy.fm.af", afStr);
        if (known.length > 0) m.putString("xy.fm.af.known", knownStr);
        m.putString("xy.fm.afsw", mSw.enabled() ? "1" : "0");
        if (pi >= 0) m.putString("xy.fm.pi", String.format(java.util.Locale.US, "%04X", pi));
        if (artist != null) m.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        mSession.setMetadata(m.build());

        setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        if (powered) mEverPlayed = true;
        // Inactive until the radio has been on once; after that stay active (PAUSED) so a
        // launcher's play button can resume it.
        if (mEverPlayed && !mSession.isActive()) mSession.setActive(true);
    }
}
