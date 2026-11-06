package com.android.fmradio;

/**
 * Our own AF switch (the stock activeAF path stays off). Pure logic, no Android dependencies: driven by tick() from
 * the XyMediaSession poll, talks to the radio only through {@link Radio}.
 *
 * Trigger (all must hold): enabled; radio powered and not seeking/scanning/recording; PAMD <= PAMD_LOW for
 * LOW_MS (and >= 3 samples); a PI validated on this frequency within PI_MAX_AGE_MS (remembered across the fade);
 * the store has confirmed AFs for that PI; no user tune/seek for USER_QUIET_MS; no attempt for ATTEMPT_GAP_MS,
 * nor for FAIL_GAP_MS after a failed attempt; STAY_MS after a successful switch.
 * Probe: up to MAX_CAND confirmed AFs (last successful one first, then by clean hits): tune, wait SETTLE_MS, accept when
 * PAMD >= PAMD_OK and >= PAMD_GAIN above the pre-switch PAMD and the validated PI on the new frequency equals the
 * station's PI; if the PI does not show up within PI_WAIT_MS accept anyway when PAMD >= PAMD_SURE (the frequency is
 * a confirmed AF by construction). Otherwise next candidate; none left: tune back and record a failure.
 */
public final class XyAfSwitch {
    public static final int PAMD_LOW = 8;
    public static final long LOW_MS = 6000;
    public static final int LOW_SAMPLES = 3;
    public static final long PI_MAX_AGE_MS = 60000;
    public static final long USER_QUIET_MS = 20000;
    public static final long ATTEMPT_GAP_MS = 30000;
    public static final long FAIL_GAP_MS = 120000;
    public static final long STAY_MS = 60000;
    public static final int MAX_CAND = 3;
    public static final long SETTLE_MS = 1700;
    public static final long PI_WAIT_MS = 3000;
    public static final long TUNE_TIMEOUT_MS = 4000;
    public static final int PAMD_OK = 15;
    public static final int PAMD_GAIN = 4;
    public static final int PAMD_SURE = 20;

    public interface Radio {
        long now();                         // monotonic ms
        boolean powered();
        boolean busy();                     // seeking, scanning or recording
        int frequency();                    // current frequency, 10 kHz units
        int pamd();                         // -1 = unavailable
        int validatedPi();                  // PI validated on the CURRENT frequency, -1 = none
        void tune(int freq);                // our own tune (mSvc.tuneStationAsync(f/100f, 0, 0))
        int[] candidates(String pi, int exclude, int max);    // confirmed AFs in switch order
        void recordSuccess(String pi, int freq);              // store lastSuccess + persist
        void log(String s);
    }

    private enum St { IDLE, PROBE }

    private final Radio r;
    private boolean mEnabled = true;
    private St mSt = St.IDLE;

    // idle tracking
    private int mExpected = -1;             // frequency we believe the radio is on (user changes are detected against it)
    private long mUserAt = Long.MIN_VALUE / 2;
    private long mOwnUntil = Long.MIN_VALUE / 2;   // our own async tune may still be in flight until then
    private long mLowSince = -1; private int mLowN;
    private int mPi = -1; private long mPiAt = Long.MIN_VALUE / 2; private int mPiFreq = -1;
    private long mAttemptAt = Long.MIN_VALUE / 2, mFailAt = Long.MIN_VALUE / 2, mSwitchAt = Long.MIN_VALUE / 2;

    // probe state
    private int mOrig, mOrigPamd, mProbePi; private String mProbePiHex;
    private int[] mCand; private int mIdx; private long mTunedAt; private boolean mGate;

    public XyAfSwitch(Radio radio) { r = radio; }

    public void setEnabled(boolean on) {
        if (on == mEnabled) return;
        mEnabled = on;
        r.log("AF switch " + (on ? "ON" : "OFF"));
        if (!on && mSt == St.PROBE) abort("disabled", true);
    }
    public boolean enabled() { return mEnabled; }
    /** true while probing: the caller should freeze what it publishes and poll fast */
    public boolean probing() { return mSt == St.PROBE; }
    /** the frequency to publish while probing */
    public int origFreq() { return mOrig; }
    public long lastUserTuneAt() { return mUserAt; }

    private static String hex(int pi) { return XyPiTracker.hex(pi); }

    /** One poll. */
    public void tick() {
        long now = r.now();
        int f = r.frequency();
        boolean ok = r.powered() && !r.busy();
        if (!r.powered() || r.busy()) { mUserAt = now; }                  // seek/scan/power change counts as user activity
        if (mSt == St.PROBE) { probeTick(now, f, ok); return; }

        // user tune detection: the frequency moved and it was not us
        if (mExpected >= 0 && f != mExpected && r.powered() && now < mOwnUntil) return;   // our tune is still going through
        if (mExpected >= 0 && f != mExpected && r.powered()) {
            mUserAt = now;
            r.log("user tune " + mExpected + " -> " + f);
        }
        mExpected = f;

        int pi = r.validatedPi();
        if (pi >= 0) { mPi = pi; mPiAt = now; mPiFreq = f; }
        int pamd = r.pamd();
        if (ok && pamd >= 0 && pamd <= PAMD_LOW) { if (mLowSince < 0) { mLowSince = now; mLowN = 0; } mLowN++; }
        else { mLowSince = -1; mLowN = 0; }
        if (!mEnabled || !ok) return;
        if (mLowSince < 0 || now - mLowSince < LOW_MS || mLowN < LOW_SAMPLES) return;
        if (mPi < 0 || mPiFreq != f || now - mPiAt > PI_MAX_AGE_MS) return;
        if (now - mUserAt < USER_QUIET_MS) return;
        if (now - mAttemptAt < ATTEMPT_GAP_MS || now - mFailAt < FAIL_GAP_MS || now - mSwitchAt < STAY_MS) return;
        String piHex = hex(mPi);
        int[] cand = r.candidates(piHex, f, MAX_CAND);
        if (cand.length == 0) return;
        mAttemptAt = now;
        mOrig = f; mOrigPamd = pamd; mProbePi = mPi; mProbePiHex = piHex; mCand = cand; mIdx = 0;
        StringBuilder sb = new StringBuilder();
        for (int c : cand) sb.append(' ').append(c);
        r.log("TRIGGER freq=" + f + " pi=" + piHex + " pamd=" + pamd + " low for " + (now - mLowSince) + "ms, candidates:" + sb);
        mLowSince = -1; mLowN = 0;
        mSt = St.PROBE;
        startCandidate(now);
    }

    private void startCandidate(long now) {
        int c = mCand[mIdx];
        mTunedAt = now; mGate = false;
        mExpected = c;
        r.log("probe " + (mIdx + 1) + "/" + mCand.length + ": tune " + c);
        mOwnUntil = now + TUNE_TIMEOUT_MS;
        r.tune(c);
    }

    private void probeTick(long now, int f, boolean ok) {
        int c = mCand[mIdx];
        if (!ok) { abort("radio busy/off", false); return; }
        if (f != c && f != mOrig) { mUserAt = now; abort("user tuned to " + f + " during probe", false); return; }
        long el = now - mTunedAt;
        if (f != c) {                                     // tune not through yet
            if (el > TUNE_TIMEOUT_MS) { r.log("probe " + c + ": tune timeout"); nextCandidate(now); }
            return;
        }
        if (el < SETTLE_MS) return;
        int pamd = r.pamd();
        if (!mGate) {
            mGate = true;
            boolean good = pamd >= PAMD_OK && pamd >= mOrigPamd + PAMD_GAIN;
            r.log("probe " + c + ": pamd=" + pamd + " (was " + mOrigPamd + ") " + (good ? "good" : "REJECT"));
            if (!good) { nextCandidate(now); return; }
        }
        int pi = r.validatedPi();
        if (pi >= 0) {
            if (pi == mProbePi) { accept(now, c, "PI " + hex(pi) + " matches, pamd=" + pamd); }
            else { r.log("probe " + c + ": PI " + hex(pi) + " != " + mProbePiHex + " REJECT"); nextCandidate(now); }
            return;
        }
        if (el >= SETTLE_MS + PI_WAIT_MS) {
            if (pamd >= PAMD_SURE) accept(now, c, "no PI in " + PI_WAIT_MS + "ms, confirmed AF and pamd=" + pamd + " >= " + PAMD_SURE);
            else { r.log("probe " + c + ": no PI, pamd=" + pamd + " < " + PAMD_SURE + " REJECT"); nextCandidate(now); }
        }
    }

    private void accept(long now, int c, String why) {
        r.log("SWITCH " + mOrig + " -> " + c + ": " + why + "; staying >= " + STAY_MS / 1000 + " s");
        mSt = St.IDLE;
        mSwitchAt = now;
        mExpected = c;
        mPi = mProbePi; mPiAt = now; mPiFreq = c;
        r.recordSuccess(mProbePiHex, c);
    }

    private void nextCandidate(long now) {
        if (++mIdx < mCand.length) { startCandidate(now); return; }
        r.log("no candidate passed: tune back to " + mOrig + ", failure recorded");
        mFailAt = now;
        mSt = St.IDLE;
        mExpected = mOrig; mOwnUntil = now + TUNE_TIMEOUT_MS;
        r.tune(mOrig);
    }

    /** end a probe early; backToOrig: retune the original frequency (not after a user tune) */
    private void abort(String why, boolean backToOrig) {
        r.log("probe aborted: " + why);
        mSt = St.IDLE;
        mFailAt = r.now();
        if (backToOrig && r.powered() && r.frequency() != mOrig) { mExpected = mOrig; mOwnUntil = r.now() + TUNE_TIMEOUT_MS; r.tune(mOrig); }
        else mExpected = r.frequency();
    }
}
