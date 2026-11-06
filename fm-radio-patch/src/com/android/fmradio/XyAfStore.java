package com.android.fmradio;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent per-PI memory of the AF frequencies seen in the live (noisy) AF list. Pure Java, no Android
 * dependencies. Per PI (hex) and frequency (10 kHz units): hits = polls the freq appeared in (history, counted at
 * most once per HIT_WINDOW_MS); clean = such hits taken at bler < CLEAN_BLER; sessions = number of separate tune
 * sessions (one continuous stay on a frequency, id given by the caller) with a clean hit; first/last = wall-clock ms;
 * tuned = polls where it was the tuned frequency of a validated PI, with the best RSSI there (-1 = none).
 * Confirmed (v8 rules) = clean >= CONFIRM_HITS and sessions >= CONFIRM_SESSIONS.
 * Per PI also lastSuccess (frequency + wall ms of our last successful own AF switch to it).
 * File format v2 (text): first line "#xyaf2", then per PI
 * "pi TAB lastSeen TAB lsFreq TAB lsAt TAB f,hits,first,last,tuned,bestRssi,clean,sessions,lastSess ; f,...".
 * The v1 format (no header, "pi TAB lastSeen TAB f,hits,first,last,tuned,bestRssi;...") is still read and migrated:
 * PI 31E0 is dropped, every entry becomes unconfirmed (hits kept as history, clean=0, sessions=0).
 */
public final class XyAfStore {
    public static final int CONFIRM_HITS = 3;
    public static final int CONFIRM_SESSIONS = 2;
    public static final int CLEAN_BLER = 25;           // a hit counts as clean only below this bler
    public static final String HEADER = "#xyaf2";
    public static final String FIX1 = "#fix1";           // one-off cleanup done: D2DE@9870 polluted by the v8.1 stale-PI bug
    public static final long HIT_WINDOW_MS = 10000;
    public static final long WRITE_EVERY_MS = 30000;
    public static final int MAX_PIS = 64;
    public static final int MAX_FREQS = 30;

    public interface Log { void i(String s); }

    private static final class Entry {
        int hits, tuned, bestRssi = -1, clean, sessions;
        long first, last, lastCounted, lastClean, lastSess;
    }
    private static final class PiRec {
        long lastSeen; int lsFreq; long lsAt;
        final Map<Integer, Entry> f = new HashMap<Integer, Entry>();
    }

    private final Log mLog;
    private final Map<String, PiRec> mPis = new HashMap<String, PiRec>();
    private boolean mDirty;
    private boolean mMigrated;
    private long mLastWrite = Long.MIN_VALUE / 2;

    public XyAfStore(Log log) { mLog = log; }

    private PiRec rec(String pi, long now) {
        PiRec r = mPis.get(pi);
        if (r == null) {
            r = new PiRec(); mPis.put(pi, r);
            if (mPis.size() > MAX_PIS) {                       // LRU by lastSeen
                String oldest = null; long ol = Long.MAX_VALUE;
                for (Map.Entry<String, PiRec> e : mPis.entrySet())
                    if (!e.getKey().equals(pi) && e.getValue().lastSeen < ol) { ol = e.getValue().lastSeen; oldest = e.getKey(); }
                if (oldest != null) mPis.remove(oldest);
            }
        }
        r.lastSeen = now;
        return r;
    }

    private Entry entry(PiRec r, int freq, long now) {
        Entry e = r.f.get(freq);
        if (e == null) {
            if (r.f.size() >= MAX_FREQS) {                     // drop the weakest: fewest hits, then oldest
                Integer worst = null; Entry we = null;
                for (Map.Entry<Integer, Entry> x : r.f.entrySet()) {
                    Entry c = x.getValue();
                    if (x.getKey() == r.lsFreq) continue;
                    if (we == null || c.clean < we.clean || (c.clean == we.clean && c.last < we.last)) { worst = x.getKey(); we = c; }
                }
                if (worst != null) r.f.remove(worst);
            }
            e = new Entry(); e.first = now; r.f.put(freq, e);
        }
        e.last = now;
        return e;
    }

    private static boolean confirmedE(Entry e) { return e.clean >= CONFIRM_HITS && e.sessions >= CONFIRM_SESSIONS; }

    /** PI as int, -1 if not 4 hex digits */
    static int piVal(String pi) {
        try { return pi.length() == 4 ? Integer.parseInt(pi, 16) : -1; } catch (NumberFormatException e) { return -1; }
    }

    /** true if a is a one-bit shift (either direction, either filler bit) of b */
    public static boolean isOneBitShift(int a, int b) {
        if (a < 0 || b < 0 || a == b) return false;
        return (a & 0xFFFE) == ((b << 1) & 0xFFFF) || (a & 0x7FFF) == (b >> 1);
    }

    /** true if pi is a one-bit shift of an already-known PI that has >= CONFIRM_HITS confirmed AFs */
    public synchronized boolean isShiftOfKnown(String pi) {
        int a = piVal(pi);
        if (a < 0) return false;
        for (String k : mPis.keySet()) {
            if (k.equals(pi) || !isOneBitShift(a, piVal(k))) continue;
            if (confirmed(k).length >= CONFIRM_HITS) return true;
        }
        return false;
    }

    /**
     * One poll with a validated PI. af = live list (the caller passes only fresh data: not stale after a tune, no
     * bit-slip; may be empty), tunedFreq = current frequency, rssi = radio RSSI or -1, bler = RDS bler (-1 unknown,
     * never clean), session = id of the current tune session (any value that differs between sessions).
     * A PI that is a one-bit shift of a known PI is ignored unless bler == 0.
     * @return frequencies that became confirmed in this call
     */
    public synchronized List<Integer> observe(long now, String pi, int tunedFreq, int[] af, int rssi, int bler, long session) {
        List<Integer> fresh = new ArrayList<Integer>();
        if (pi == null || pi.length() == 0) return fresh;
        if (bler != 0 && isShiftOfKnown(pi)) {
            mLog.i("PI " + pi + " ignored: one-bit shift of a known PI (bler=" + bler + ")");
            return fresh;
        }
        PiRec r = rec(pi, now);
        if (tunedFreq > 0) {
            Entry t = entry(r, tunedFreq, now);
            t.tuned++;
            if (rssi > t.bestRssi) t.bestRssi = rssi;
        }
        if (af != null) {
            for (int f : af) {
                if (f <= 0) continue;
                Entry e = entry(r, f, now);
                boolean was = confirmedE(e);
                if (e.hits == 0 || now - e.lastCounted >= HIT_WINDOW_MS) { e.hits++; e.lastCounted = now; }
                if (bler >= 0 && bler < CLEAN_BLER && (e.clean == 0 || now - e.lastClean >= HIT_WINDOW_MS)) {
                    e.clean++; e.lastClean = now;
                    if (e.sessions == 0 || e.lastSess != session) { e.sessions++; e.lastSess = session; }
                }
                if (!was && confirmedE(e)) fresh.add(f);
            }
        }
        mDirty = true;
        for (int f : fresh) mLog.i("PI " + pi + " confirmed AF " + f + " (clean=" + r.f.get(f).clean + " sessions=" + r.f.get(f).sessions + ")");
        return fresh;
    }

    /** records a successful own switch to freq for pi */
    public synchronized void setLastSuccess(String pi, int freq, long now) {
        PiRec r = rec(pi, now);
        r.lsFreq = freq; r.lsAt = now; mDirty = true;
    }

    /** frequency of the last successful own switch for pi, 0 if none */
    public synchronized int lastSuccess(String pi) {
        PiRec r = pi == null ? null : mPis.get(pi);
        return r == null ? 0 : r.lsFreq;
    }

    public synchronized long lastSuccessAt(String pi) {
        PiRec r = pi == null ? null : mPis.get(pi);
        return r == null ? 0 : r.lsAt;
    }

    /** switch candidates: confirmed AFs except exclude; lastSuccess first, then by clean hits (desc, then freq); at most max */
    public synchronized int[] candidates(String pi, int exclude, int max) {
        PiRec r = pi == null ? null : mPis.get(pi);
        if (r == null) return new int[0];
        List<Integer> l = new ArrayList<Integer>();
        for (Map.Entry<Integer, Entry> e : r.f.entrySet()) if (confirmedE(e.getValue()) && e.getKey() != exclude) l.add(e.getKey());
        final PiRec fr = r;
        Collections.sort(l, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                boolean la = a == fr.lsFreq, lb = b == fr.lsFreq;
                if (la != lb) return la ? -1 : 1;
                int ha = fr.f.get(a).clean, hb = fr.f.get(b).clean;
                if (ha != hb) return ha > hb ? -1 : 1;
                return a < b ? -1 : a > b ? 1 : 0;
            }
        });
        int n = Math.min(max, l.size());
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = l.get(i);
        return a;
    }

    /** confirmed freqs for a PI, sorted ascending */
    public synchronized int[] confirmed(String pi) {
        PiRec r = pi == null ? null : mPis.get(pi);
        if (r == null) return new int[0];
        List<Integer> l = new ArrayList<Integer>();
        for (Map.Entry<Integer, Entry> e : r.f.entrySet()) if (confirmedE(e.getValue())) l.add(e.getKey());
        Collections.sort(l);
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    public synchronized int piCount() { return mPis.size(); }

    public synchronized boolean hasPi(String pi) { return mPis.containsKey(pi); }

    public synchronized int hits(String pi, int freq) {
        PiRec r = mPis.get(pi); Entry e = r == null ? null : r.f.get(freq);
        return e == null ? 0 : e.hits;
    }

    public synchronized int cleanHits(String pi, int freq) {
        PiRec r = mPis.get(pi); Entry e = r == null ? null : r.f.get(freq);
        return e == null ? 0 : e.clean;
    }

    public synchronized int sessions(String pi, int freq) {
        PiRec r = mPis.get(pi); Entry e = r == null ? null : r.f.get(freq);
        return e == null ? 0 : e.sessions;
    }

    public synchronized int bestRssi(String pi, int freq) {
        PiRec r = mPis.get(pi); Entry e = r == null ? null : r.f.get(freq);
        return e == null ? -1 : e.bestRssi;
    }

    public synchronized boolean migrated() { return mMigrated; }

    /** one human-readable line per PI, most recently seen first */
    public synchronized List<String> dumpLines() {
        List<Map.Entry<String, PiRec>> es = new ArrayList<Map.Entry<String, PiRec>>(mPis.entrySet());
        Collections.sort(es, new java.util.Comparator<Map.Entry<String, PiRec>>() {
            @Override public int compare(Map.Entry<String, PiRec> a, Map.Entry<String, PiRec> b) {
                long x = a.getValue().lastSeen, y = b.getValue().lastSeen;
                return x < y ? 1 : x > y ? -1 : 0;
            }
        });
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, PiRec> pe : es) {
            StringBuilder sb = new StringBuilder("PI " + pe.getKey() + " lastSeen=" + pe.getValue().lastSeen
                    + " lastSwitch=" + pe.getValue().lsFreq + " confirmed=[");
            int[] c = confirmed(pe.getKey());
            for (int i = 0; i < c.length; i++) sb.append(i > 0 ? "," : "").append(c[i]);
            sb.append("] freqs:");
            List<Integer> fs = new ArrayList<Integer>(pe.getValue().f.keySet());
            Collections.sort(fs);
            for (int f : fs) {
                Entry e = pe.getValue().f.get(f);
                sb.append(' ').append(f).append("(h=").append(e.hits).append(" c=").append(e.clean).append(" s=").append(e.sessions)
                  .append(" first=").append(e.first).append(" last=").append(e.last);
                if (e.tuned > 0) sb.append(" tuned=").append(e.tuned).append(" rssi=").append(e.bestRssi);
                sb.append(')');
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ---- persistence ----
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n').append(FIX1).append('\n');
        for (Map.Entry<String, PiRec> pe : mPis.entrySet()) {
            PiRec r = pe.getValue();
            sb.append(pe.getKey()).append('\t').append(r.lastSeen).append('\t').append(r.lsFreq).append('\t').append(r.lsAt).append('\t');
            boolean first = true;
            for (Map.Entry<Integer, Entry> fe : r.f.entrySet()) {
                Entry e = fe.getValue();
                if (!first) sb.append(';');
                first = false;
                sb.append(fe.getKey()).append(',').append(e.hits).append(',').append(e.first).append(',')
                  .append(e.last).append(',').append(e.tuned).append(',').append(e.bestRssi).append(',')
                  .append(e.clean).append(',').append(e.sessions).append(',').append(e.lastSess);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** replaces the content (v2, or v1 migrated); malformed lines are skipped */
    public synchronized void deserialize(String s) {
        mPis.clear();
        String[] lines = s.split("\n");
        boolean v2 = lines.length > 0 && lines[0].trim().equals(HEADER);
        mMigrated = false;
        boolean fixed = false;
        for (String l0 : lines) if (l0.trim().equals(FIX1)) fixed = true;
        for (int li = v2 ? 1 : 0; li < lines.length; li++) {
            if (lines[li].startsWith("#")) continue;
            try {
                String[] p = lines[li].split("\t", -1);
                int base = v2 ? 4 : 2;
                if (p.length < base + 1 || p[0].length() == 0) continue;
                if (!v2 && p[0].equalsIgnoreCase("31E0")) { mMigrated = true; continue; }   // bit-slipped PI of RCG (63C1)
                PiRec r = new PiRec(); r.lastSeen = Long.parseLong(p[1]);
                if (v2) { r.lsFreq = Integer.parseInt(p[2]); r.lsAt = Long.parseLong(p[3]); }
                if (p[base].length() > 0) for (String fs : p[base].split(";")) {
                    String[] q = fs.split(",");
                    Entry e = new Entry();
                    e.hits = Integer.parseInt(q[1]); e.first = Long.parseLong(q[2]); e.last = Long.parseLong(q[3]);
                    e.tuned = Integer.parseInt(q[4]); e.bestRssi = Integer.parseInt(q[5]); e.lastCounted = e.last;
                    if (v2) {
                        e.clean = Integer.parseInt(q[6]); e.sessions = Integer.parseInt(q[7]);
                        e.lastSess = Long.parseLong(q[8]); e.lastClean = e.last;
                    }
                    r.f.put(Integer.parseInt(q[0]), e);
                }
                mPis.put(p[0], r);
            } catch (RuntimeException ignored) { }
        }
        if (!fixed) {                                  // one-off: under PI D2DE (PLAY) drop the entry for 9870 (98.7)
            PiRec d = mPis.get("D2DE");
            if (d != null && d.f.remove(9870) != null) mLog.i("cleanup: removed D2DE@9870");
            if (!mPis.isEmpty()) mMigrated = true;     // rewrite with the marker
        }
        if (!v2 && !mPis.isEmpty()) mMigrated = true;
        mDirty = mMigrated;                      // rewrite old files in the new format on the next flush
        if (mMigrated) mLog.i("migrated v1 store: " + mPis.size() + " PI(s), all AFs unconfirmed (hits kept as history)");
    }

    public void load(File file) {
        if (!file.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder(); char[] buf = new char[4096]; int n;
            try { while ((n = br.read(buf)) > 0) sb.append(buf, 0, n); } finally { br.close(); }
            deserialize(sb.toString());
        } catch (IOException e) { mLog.i("load failed: " + e); }
    }

    /** writes if dirty and (force or >= WRITE_EVERY_MS since the last write). tmp file + rename. */
    public void flush(long now, File file, boolean force) {
        String data;
        synchronized (this) {
            if (!mDirty || (!force && now - mLastWrite < WRITE_EVERY_MS)) return;
            data = serialize(); mDirty = false; mLastWrite = now;
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try { w.write(data); } finally { w.close(); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (IOException e) {
            mLog.i("write failed: " + e);
            synchronized (this) { mDirty = true; }
        }
    }
}
