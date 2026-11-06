package com.android.fmradio;

/**
 * Turns raw, cached PI reads into a validated PI. Pure logic, no Android dependencies.
 * Rules (see README): clear on tune; reads (PI and AF) are ignored for SETTLE_MS after a tune; reads at
 * bler >= BLIP_BLER (40) are skipped (neither counted nor breaking a run; 3 equal reads protect below that); accept after >= MIN_RUN identical reads on one frequency; a value that equals a
 * stale (pre-tune) PI additionally needs low BLER on every read of the run and evidence that RDS is live
 * (bler > 0 seen after the settle window, never time alone: bler 0 with an unchanged struct means nothing is
 * received), or proof that libfmjni's struct was cleared (noteCleared) and then read back empty before this value appeared; once validated, switching needs MIN_RUN identical reads.
 * AF lists get the same treatment through afFresh().
 */
public final class XyPiTracker {
    public static final int MIN_RUN = 3;
    public static final long SETTLE_MS = 2500;          // blanking after a tune: libfmjni keeps the old station's RDS struct
    public static final int BLIP_BLER = 40;             // bler at or above this: read is skipped (does not count, does not reset a run)
    public static final int PAMD_DEAD = 8;              // PAMD at or below this = no usable signal
    public static final long MIN_SPACING_MS = 300;
    public static final int BLER_OK = 50;
    public static final long FAST_WINDOW_MS = 15000;

    public interface Log { void i(String s); }

    private final Log mLog;
    private int mFreq = -1;
    private long mTuneAt;
    private int mPublished = -1;
    private int mStaleA = -1, mStaleB = -1;     // last raw PI on the previous frequency, first raw read after the tune
    private boolean mFirstRead;
    private boolean mCleared, mProof;           // struct cleared after the tune / read back empty afterwards = later values are fresh
    private long mClearedAt;
    private boolean mBlerPos;                    // bler > 0 seen after the settle window: the decoder is receiving blocks
    private int[] mLastRawAf = new int[0], mAfStale = new int[0];
    private int mRunMinPamd = Integer.MAX_VALUE;
    private int mLastRaw = -1;                   // last raw seen on the current frequency
    private int mCand = -1, mRun; private long mRunFirstAt, mLastReadAt; private int mRunMaxBler = -1, mRunBlerN;

    public XyPiTracker(Log log) { mLog = log; }

    public int published() { return mPublished; }

    public long tuneAt() { return mTuneAt; }

    /** the caller zeroed libfmjni's cached PI/AF just now (after the tune) */
    public void noteCleared(long now) { mCleared = true; mClearedAt = now; mProof = false; }

    public boolean freshProof() { return mProof; }

    /** remember the last raw AF list read (any state); on the next tune it becomes the stale list */
    public void noteRawAf(int[] af) { mLastRawAf = af == null ? new int[0] : af; }

    /** true while a validation is pending and the fast poll window is open */
    public boolean wantFast(long now) { return mFreq >= 0 && mPublished < 0 && now - mTuneAt < FAST_WINDOW_MS; }

    /** @return true if the freq changed (caller should clear what it published) */
    public boolean onFreq(long now, int freq) {
        if (freq == mFreq) return false;
        mStaleA = mLastRaw;
        mAfStale = mLastRawAf; mLastRawAf = new int[0]; mBlerPos = false; mCleared = false; mProof = false;
        mFreq = freq; mTuneAt = now; mFirstRead = true; mLastRaw = -1; mCand = -1; mRun = 0;
        int old = mPublished; mPublished = -1;
        mLog.i("freq=" + freq + " tune: clear published (was " + hex(old) + "), stale-from-prev=" + hex(mStaleA));
        return true;
    }

    /** one raw read (raw < 0 = none); bler < 0 = unknown/not queried. @return true if the published value changed */
    public boolean onRead(long now, int freq, int raw, int bler, int pamd) {
        if (freq != mFreq) onFreq(now, freq);
        if (mFirstRead) { mFirstRead = false; mStaleB = raw; mLog.i("freq=" + freq + " pre-tune/first PI=" + hex(raw)); }
        if (raw >= 0) mLastRaw = raw;
        if (now - mTuneAt >= SETTLE_MS && bler > 0) mBlerPos = true;
        if (mCleared && !mProof && raw < 0 && now > mClearedAt) { mProof = true; mLog.i("freq=" + freq + " cleared struct read back empty: later PI/AF are fresh"); }
        if (raw < 0) { mCand = -1; mRun = 0; return false; }
        if (now - mTuneAt < SETTLE_MS) return false;                     // reads inside the settle window never count
        if (bler >= BLIP_BLER) {                                         // bit-slip window: a slipped PI can repeat for minutes
            mLog.i("freq=" + freq + " bler=" + bler + " >= " + BLIP_BLER + ", read skipped");
            return false;
        }
        if (mRun > 0 && raw == mCand) {
            if (now - mLastReadAt < MIN_SPACING_MS) return false;       // ignore bursty duplicate reads
            mRun++;
        } else {
            if (mRun > 0) mLog.i("freq=" + freq + " candidate " + hex(mCand) + " broken by " + hex(raw));
            mCand = raw; mRun = 1; mRunFirstAt = now; mRunMaxBler = -1; mRunBlerN = 0; mRunMinPamd = Integer.MAX_VALUE;
        }
        if (pamd >= 0) mRunMinPamd = Math.min(mRunMinPamd, pamd);
        if (bler >= 0) { mRunMaxBler = Math.max(mRunMaxBler, bler); mRunBlerN++; }
        mLastReadAt = now;
        mLog.i("freq=" + freq + " cand=" + hex(raw) + " run=" + mRun + " bler=" + bler + " t+" + (now - mTuneAt) + "ms");
        if (raw == mPublished) return false;
        if (mRun < MIN_RUN || mRunFirstAt - mTuneAt < SETTLE_MS) return false;
        if (raw == mStaleA || raw == mStaleB) {
            boolean haveBler = mRunBlerN >= 2;                           // BLER is decisive when it is available
            boolean blerOk = haveBler ? mRunMaxBler < BLER_OK : true;
            boolean live = liveEvidence(haveBler);
            if (!(blerOk && live)) { mLog.i("freq=" + freq + " " + hex(raw) + " equals stale PI, rejected/waiting (maxBler=" + mRunMaxBler + " n=" + mRunBlerN + " blerSeen>0=" + mBlerPos + ")"); return false; }
            mLog.i("freq=" + freq + " stale-equal PI accepted (bler>0 seen=" + mBlerPos + ")");
        }
        mLog.i("freq=" + freq + " VALIDATED pi=" + hex(raw) + " (was " + hex(mPublished) + ")");
        mPublished = raw;
        return true;
    }

    /** proof that the RDS data is live and not the previous station's leftover */
    private boolean liveEvidence(boolean haveBler) {
        return mProof || (haveBler && mBlerPos);
    }

    /**
     * Is this raw AF list usable (for publishing and for the store)? Not within SETTLE_MS of a tune, not in a
     * bit-slip window (bler >= BLIP_BLER), not at a dead signal (pamd <= PAMD_DEAD), and if it is identical to the
     * previous frequency's last list it needs live evidence (the struct may simply not have been overwritten).
     */
    public boolean afFresh(long now, int[] af, int bler, int pamd) {
        if (af == null || af.length == 0 || mFreq < 0) return false;
        if (now - mTuneAt < SETTLE_MS) return false;
        if (bler >= BLIP_BLER) return false;
        if (pamd >= 0 && pamd <= PAMD_DEAD) return false;
        if (java.util.Arrays.equals(af, mAfStale)) return liveEvidence(bler >= 0);
        return true;
    }

    /** BLER is only needed when the current candidate would be a stale-equal value */
    public boolean needsBler() { return mRun > 0 && (mCand == mStaleA || mCand == mStaleB); }
    public boolean needsBlerFor(int raw) { return raw >= 0 && (raw == mStaleA || raw == mStaleB); }

    /**
     * The service sometimes reports the frequency for one poll in 100 kHz units (987, 922, 1060) instead of 10 kHz units
     * (9870, ...). Once the radio was on FM (lastGood >= 8750), 875..1080 is scaled by 10 and any other value below 8750
     * is held at the last good frequency, so a glitch never registers as a tune.
     */
    public static int normFreq(int raw, int lastGood) {
        if (lastGood < 8750 || raw >= 8750 || raw < 0) return raw;
        if (raw >= 875 && raw <= 1080) return raw * 10;
        return lastGood;
    }

    public static String hex(int v) { return v < 0 ? "none" : String.format(java.util.Locale.US, "%04X", v); }
}
