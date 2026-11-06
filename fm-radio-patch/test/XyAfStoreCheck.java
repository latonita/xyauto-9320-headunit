import com.android.fmradio.XyAfStore;
import com.android.fmradio.XyPiTracker;
import java.io.File;
import java.util.List;

/** main()-style self-check of XyAfStore and XyPiTracker, run by build.py. Exits non-zero on failure. */
public class XyAfStoreCheck {
    static void check(boolean c, String m) { if (!c) { System.err.println("FAIL: " + m); System.exit(1); } }
    static XyAfStore quiet() { return new XyAfStore(new XyAfStore.Log() { public void i(String x) { } }); }
    static XyPiTracker tracker() { return new XyPiTracker(new XyPiTracker.Log() { public void i(String x) { } }); }

    public static void main(String[] a) throws Exception {
        store();
        migration();
        shift();
        tracker1();
        System.out.println("XyAfStoreCheck OK");
    }

    static void store() throws Exception {
        XyAfStore s = quiet();
        long t = 1000000;
        int[] af = {8950, 9810};
        // one session, clean data, a stuck list polled every 2 s: hits count once per 10 s, but 1 session is not enough
        for (int k = 0; k < 20; k++) s.observe(t + k * 2000L, "20FE", 10600, af, 40 + k, 3, 111);
        check(s.hits("20FE", 8950) == 4, "stuck list hits=" + s.hits("20FE", 8950));
        check(s.cleanHits("20FE", 8950) == 4 && s.sessions("20FE", 8950) == 1, "one session");
        check(s.confirmed("20FE").length == 0, "one session never confirms");
        // second session confirms (clean >= 3 and sessions >= 2)
        List<Integer> fresh = s.observe(t + 100000, "20FE", 9870, af, 40, 3, 222);
        check(fresh.size() == 2 && s.confirmed("20FE").length == 2, "second session confirms: " + fresh);
        // bler gating: hits at bler 10..19 count as history only
        XyAfStore g = quiet();
        for (int k = 0; k < 10; k++) g.observe(t + k * 11000L, "AAAA", 9000, new int[] {8800}, -1, 30, 1 + k);
        check(g.hits("AAAA", 8800) == 10 && g.cleanHits("AAAA", 8800) == 0 && g.confirmed("AAAA").length == 0, "bler 30 is not clean");
        for (int k = 0; k < 10; k++) g.observe(t + 200000 + k * 11000L, "AAAA", 9000, new int[] {8810}, -1, -1, 1 + k);
        check(g.cleanHits("AAAA", 8810) == 0, "unknown bler is not clean");
        for (int k = 0; k < 3; k++) g.observe(t + 400000 + k * 11000L, "AAAA", 9000, new int[] {8820}, -1, 24, 50);
        check(g.cleanHits("AAAA", 8820) == 3 && g.sessions("AAAA", 8820) == 1 && g.confirmed("AAAA").length == 0, "3 clean hits in 1 session: unconfirmed");
        g.observe(t + 500000, "AAAA", 9000, new int[] {8820}, -1, 0, 51);
        check(g.confirmed("AAAA").length == 1 && g.confirmed("AAAA")[0] == 8820, "2nd session confirms");
        // 2 sessions but only 2 clean hits: unconfirmed
        XyAfStore h = quiet();
        h.observe(t, "BBBB", 9000, new int[] {8800}, -1, 0, 1);
        h.observe(t + 20000, "BBBB", 9000, new int[] {8800}, -1, 0, 2);
        check(h.cleanHits("BBBB", 8800) == 2 && h.sessions("BBBB", 8800) == 2 && h.confirmed("BBBB").length == 0, "needs 3 hits in total");
        h.observe(t + 40000, "BBBB", 9000, new int[] {8800}, -1, 0, 2);
        check(h.confirmed("BBBB").length == 1, "3 hits + 2 sessions");
        // tuned observation: rssi, no hits
        check(s.hits("20FE", 10600) == 0 && s.bestRssi("20FE", 10600) == 59, "tuned rssi " + s.bestRssi("20FE", 10600));
        // lastSuccess + candidate order
        XyAfStore c = quiet();
        int[] fs = {8800, 8900, 9000, 9100};
        for (int sess = 1; sess <= 2; sess++)
            for (int k = 0; k < 4; k++)
                for (int i = 0; i < fs.length; i++) {
                    // 9000 gets the most clean hits, then 8900, ...
                    if (k < 1 + (i == 2 ? 3 : i == 1 ? 2 : i == 3 ? 0 : 0) || sess == 2 && k < 3)
                        c.observe(t + sess * 1000000L + k * 11000L, "CCCC", 9500, new int[] {fs[i]}, -1, 1, sess);
                }
        int[] cand = c.candidates("CCCC", 8800, 3);
        check(c.confirmed("CCCC").length == 4, "4 confirmed " + c.confirmed("CCCC").length);
        check(cand.length == 3 && cand[0] != 8800 && cand[1] != 8800 && cand[2] != 8800, "excludes current");
        check(cand[0] == 9000 && cand[1] == 8900 && cand[2] == 9100, "order by clean hits: " + cand[0] + "," + cand[1] + "," + cand[2]);
        c.setLastSuccess("CCCC", 9100, 5);
        cand = c.candidates("CCCC", 8800, 3);
        check(cand[0] == 9100, "lastSuccess first: " + cand[0]);
        check(c.lastSuccess("CCCC") == 9100 && c.lastSuccessAt("CCCC") == 5, "lastSuccess data");
        XyAfStore c2 = quiet();
        c2.deserialize(c.serialize());
        check(c2.serialize().equals(c.serialize()), "v2 roundtrip");
        check(c2.lastSuccess("CCCC") == 9100 && c2.candidates("CCCC", 8800, 3)[0] == 9100, "lastSuccess survives file");
        check(c2.confirmed("CCCC").length == 4, "confirmed survive file");
        c2.deserialize("garbage\nXX\t1\t5,1,2\n");
        check(c2.piCount() == 0, "malformed skipped");
        // caps
        XyAfStore p = quiet();
        for (int i = 0; i < 70; i++) p.observe(1000 + i, String.format("%04X", i), 9000, new int[] {8800}, -1, 0, i);
        check(p.piCount() == 64, "pi cap " + p.piCount());
        check(p.hits("0045", 8800) == 1 && p.hits("0000", 8800) == 0, "LRU evicts oldest");
        XyAfStore d = quiet();
        int[] many = new int[40];
        for (int i = 0; i < 40; i++) many[i] = 8800 + i * 10;
        d.observe(5, "AAAA", 8700, many, -1, 0, 1);
        check(d.dumpLines().get(0).split("\\(h=").length - 1 <= 30, "freq cap");
        // flush / load
        File f = File.createTempFile("xyaf", ".txt");
        f.delete();
        XyAfStore e = quiet();
        e.observe(1, "BBBB", 9000, new int[] {8800}, 3, 0, 1);
        e.flush(10, f, false);
        check(f.exists(), "first flush writes");
        long len = f.length();
        e.observe(20, "BBBB", 9000, new int[] {8810}, 3, 0, 1);
        e.flush(100, f, false);
        check(f.length() == len, "throttled within 30 s");
        e.flush(100, f, true);
        check(f.length() > len, "forced flush writes");
        XyAfStore l = quiet();
        l.load(f);
        check(l.serialize().equals(e.serialize()), "file roundtrip");
        f.delete();
    }

    static void migration() throws Exception {
        // v1 file: PI 20FE with 3 confirmed-by-old-rules AFs, bit-slipped 31E0 with 19 entries
        String v1 = "20FE\t5000\t8950,4,100,200,0,-1;9810,5,100,200,0,-1;10600,0,100,200,7,59\n"
                + "31E0\t6000\t9220,9,1,2,0,-1;9490,9,1,2,0,-1\n"
                + "D2DE\t7000\t8820,3,1,2,2,50\n";
        XyAfStore s = quiet();
        s.deserialize(v1);
        check(s.migrated(), "migrated flag");
        check(!s.hasPi("31E0"), "31E0 dropped");
        check(s.piCount() == 2, "other PIs kept");
        check(s.hits("20FE", 9810) == 5 && s.hits("20FE", 8950) == 4, "hits kept as history");
        check(s.confirmed("20FE").length == 0 && s.confirmed("D2DE").length == 0, "all unconfirmed after migration");
        check(s.cleanHits("20FE", 9810) == 0 && s.sessions("20FE", 9810) == 0, "clean/sessions reset");
        check(s.bestRssi("20FE", 10600) == 59, "tuned info kept");
        check(s.lastSuccess("20FE") == 0, "no lastSuccess from old data");
        check(s.serialize().startsWith(XyAfStore.HEADER + "\n"), "rewritten as v2");
        // re-confirm under the new rules
        s.observe(1000000, "20FE", 9870, new int[] {9810}, -1, 0, 1);
        s.observe(1011000, "20FE", 9870, new int[] {9810}, -1, 0, 1);
        s.observe(1022000, "20FE", 9870, new int[] {9810}, -1, 0, 2);
        check(s.confirmed("20FE").length == 1, "re-confirmed under new rules");
        // file: old file is read, and rewritten dirty
        File f = File.createTempFile("xyaf", ".txt");
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        o.write(v1.getBytes("UTF-8")); o.close();
        XyAfStore l = quiet();
        l.load(f);
        l.flush(1, f, false);                  // migration marks the store dirty, so the first flush rewrites it
        String now = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        check(now.startsWith(XyAfStore.HEADER), "file rewritten as v2");
        XyAfStore l2 = quiet(); l2.load(f);
        check(!l2.migrated() && l2.piCount() == 2 && l2.hits("20FE", 9810) == 5, "v2 file reloads");
        // one-off cleanup: D2DE@9870 removed once (v1 and v2-without-marker), 20FE untouched, other D2DE entries kept
        String polluted = "20FE\t5000\t10600,2,1,2,3,-60;8950,4,1,2,0,-1\nD2DE\t7000\t9870,6,1,2,9,-78;8790,3,1,2,2,50\n";
        XyAfStore q = quiet(); q.deserialize(polluted);
        check(q.hits("D2DE", 9870) == 0 && q.bestRssi("D2DE", 9870) == -1 && q.hits("D2DE", 8790) == 3, "D2DE@9870 removed (v1)");
        check(q.hits("20FE", 10600) == 2 && q.hits("20FE", 8950) == 4, "20FE kept");
        XyAfStore q2 = quiet(); q2.deserialize(q.serialize());
        q2.observe(1, "D2DE", 9870, new int[0], -70, 0, 1);              // legitimately learned later
        XyAfStore q3 = quiet(); q3.deserialize(q2.serialize());
        check(q3.serialize().contains("9870,0,") , "cleanup does not repeat once the marker is stored");
        f.delete();
    }

    static void shift() {
        check(XyAfStore.isOneBitShift(0x31E0, 0x63C1), "31E0 is 63C1 >> 1");
        check(XyAfStore.isOneBitShift(0x63C0, 0x31E0) || XyAfStore.isOneBitShift(0x63C1, 0x31E0), "63C0 is 31E0 << 1");
        check(!XyAfStore.isOneBitShift(0x63C1, 0x63C1), "equal is not a shift");
        check(!XyAfStore.isOneBitShift(0x20FE, 0x63C1), "unrelated");
        XyAfStore s = quiet();
        // 63C1 with 3 confirmed AFs
        for (int sess = 1; sess <= 2; sess++)
            for (int k = 0; k < 3; k++)
                s.observe(1000000L * sess + k * 11000L, "63C1", 9220, new int[] {9490, 9630, 9810}, -1, 0, sess);
        check(s.confirmed("63C1").length == 3, "63C1 has 3 confirmed");
        check(s.isShiftOfKnown("31E0"), "31E0 flagged");
        s.observe(5000000, "31E0", 9220, new int[] {9490, 9630}, -1, 44, 9);
        check(!s.hasPi("31E0"), "31E0 at bler 44 ignored");
        s.observe(5001000, "31E0", 9220, new int[] {9490}, -1, -1, 9);
        check(!s.hasPi("31E0"), "31E0 at unknown bler ignored");
        s.observe(5002000, "31E0", 9220, new int[] {9490}, -1, 0, 9);
        check(s.hasPi("31E0"), "31E0 at bler 0 accepted");
        // known PI with fewer than 3 confirmed does not block its shift
        XyAfStore t = quiet();
        for (int sess = 1; sess <= 2; sess++)
            for (int k = 0; k < 3; k++) t.observe(1000000L * sess + k * 11000L, "63C1", 9220, new int[] {9490, 9630}, -1, 0, sess);
        check(!t.isShiftOfKnown("31E0"), "only 2 confirmed: no block");
    }

    // ---- PI tracker ----
    static void tracker1() {
        // A. stale RDS after a tune is ignored for 2.5 s; a PI differing from the stale ones validates after 3 reads
        XyPiTracker t = tracker();
        long n = 0;
        t.onFreq(n, 9870);
        t.onRead(n, 9870, -1, 0, 25);
        for (int k = 0; k < 8; k++) { n += 500; t.onRead(n, 9870, 0x20FE, 0, 25); }
        check(t.published() == 0x20FE, "first station validates (no stale yet)");
        n += 1000;
        t.onFreq(n, 10600);
        long tune = n;
        // leftover 20FE for the first 2.4 s: nothing counts
        for (int k = 0; k < 4; k++) { n += 500; t.onRead(n, 10600, 0x20FE, 0, 6); }
        check(n - tune < 2500 + 500 && t.published() < 0, "leftover ignored in blanking window (published=" + t.published() + ")");
        // dead signal keeps sending 20FE at bler 0, pamd 6: stale-equal, no bler>0, no stability evidence -> never validated
        for (int k = 0; k < 30; k++) { n += 500; t.onRead(n, 10600, 0x20FE, 0, 6); }
        check(t.published() < 0, "stale-equal at dead signal is not validated");
        // a real new PI is accepted after blanking
        XyPiTracker u = tracker();
        u.onFreq(0, 9870);
        u.onRead(0, 9870, -1, 0, 25);
        for (int k = 1; k <= 8; k++) u.onRead(k * 500L, 9870, 0x20FE, 0, 25);
        u.onFreq(5000, 9220);
        u.onRead(5000, 9220, -1, 0, 25);
        for (int k = 1; k <= 3; k++) u.onRead(5000 + k * 500L, 9220, 0x63C1, 0, 25);       // inside 2.5 s: ignored
        check(u.published() < 0, "reads inside blanking ignored even if different");
        for (int k = 6; k <= 9; k++) u.onRead(5000 + k * 500L, 9220, 0x63C1, 0, 25);
        check(u.published() == 0x63C1, "different PI validates after blanking");
        // B. bit-slip: bler >= 20 reads do not count
        XyPiTracker v = tracker();
        v.onFreq(0, 9220);
        for (int k = 1; k <= 30; k++) v.onRead(k * 500L, 9220, 0x31E0, 44, 22);
        check(v.published() < 0, "PI at bler 44 never validates");
        for (int k = 31; k <= 35; k++) v.onRead(k * 500L, 9220, 0x63C1, 3, 22);
        check(v.published() == 0x63C1, "clean reads validate");
        // slip in the middle of a run drops the run
        XyPiTracker w = tracker();
        w.onFreq(0, 9220);
        w.onRead(3000, 9220, 0x63C1, 2, 22); w.onRead(3500, 9220, 0x63C1, 2, 22);
        w.onRead(4000, 9220, 0x63C1, 45, 22);     // skipped, run stays at 2
        check(w.published() < 0, "bler 45 read does not count");
        w.onRead(4500, 9220, 0x63C1, 25, 22);     // counts (below 40): run 3
        check(w.published() == 0x63C1, "run interleaved with bler 25 and 45 still validates");
        XyPiTracker w2 = tracker();               // moderate-signal pattern from the unit: 17, 23, 45, 31, 76
        w2.onFreq(0, 9870);
        w2.onRead(0, 9870, -1, 0, 15);
        int[] bl = {17, 23, 45, 31, 76, 28, 20};
        for (int k = 0; k < bl.length; k++) w2.onRead(3000 + k * 500L, 9870, 0x20FE, bl[k], 15);
        check(w2.published() == 0x20FE, "moderate-bler (17..31) reads validate with 45/76 reads skipped");
        XyPiTracker w3 = tracker();               // garbage candidate interleaved does break the run
        w3.onFreq(0, 9870); w3.onRead(0, 9870, -1, 0, 15);
        w3.onRead(3000, 9870, 0x20FE, 20, 15); w3.onRead(3500, 9870, 0x20FE, 20, 15);
        w3.onRead(4000, 9870, 0xA0BE, 20, 15);
        check(w3.published() < 0, "different PI breaks the run");
        // C. stale-equal PI accepted with bler>0 evidence (same-station AF hop)
        XyPiTracker x = tracker();
        x.onFreq(0, 9870);
        x.onRead(0, 9870, -1, 0, 25);
        for (int k = 1; k <= 8; k++) x.onRead(k * 500L, 9870, 0x20FE, 0, 25);
        x.onFreq(5000, 10600);
        for (int k = 1; k <= 12; k++) x.onRead(5000 + k * 500L, 10600, 0x20FE, k == 8 ? 4 : 0, 25);
        check(x.published() == 0x20FE, "stale-equal accepted after bler>0 seen post-settle");
        // stale leftover with bler 0 forever (the 98.7 case: PAMD 15, nothing received) never validates, however long
        XyPiTracker y = tracker();
        y.onFreq(0, 9870);
        y.onRead(0, 9870, -1, 0, 15);
        for (int k = 1; k <= 8; k++) y.onRead(k * 500L, 9870, 0x20FE, 0, 25);
        check(y.published() == 0x20FE, "setup: first station validated");
        y.onFreq(5000, 8790);
        for (int k = 0; k <= 200; k++) y.onRead(5000 + k * 500L, 8790, 0x20FE, 0, 15);   // leftover repeated, bler 0, 100 s
        check(y.published() < 0, "stale leftover with bler 0 never validates");
        // clear proof: struct cleared after the tune and read back empty, then the same PI shows up: fresh, validates
        XyPiTracker c = tracker();
        c.onFreq(0, 9870); c.onRead(0, 9870, -1, 0, 15);
        for (int k = 1; k <= 8; k++) c.onRead(k * 500L, 9870, 0x20FE, 0, 25);
        c.onFreq(5000, 10600);
        c.onRead(5000, 10600, 0x20FE, 0, 25);               // stale leftover seen in the tune update
        c.noteCleared(5000);
        c.onRead(5500, 10600, -1, 0, 25);                   // read back empty
        check(c.freshProof(), "proof after empty read-back");
        for (int k = 1; k <= 8; k++) c.onRead(5500 + k * 500L, 10600, 0x20FE, 0, 25);
        check(c.published() == 0x20FE, "after a clear, a fresh read of the same PI validates (bler 0)");
        // no proof when the leftover is back right after the clear
        XyPiTracker d = tracker();
        d.onFreq(0, 9870); d.onRead(0, 9870, -1, 0, 15);
        for (int k = 1; k <= 8; k++) d.onRead(k * 500L, 9870, 0x20FE, 0, 25);
        d.onFreq(5000, 8790);
        d.onRead(5000, 8790, 0x20FE, 0, 15);
        d.noteCleared(5000);
        for (int k = 1; k <= 40; k++) d.onRead(5000 + k * 500L, 8790, 0x20FE, 0, 15);
        check(!d.freshProof() && d.published() < 0, "leftover reappearing after the clear is still rejected");
        // proof is reset by the next tune
        d.onFreq(30000, 9870);
        check(!d.freshProof(), "proof reset on tune");
        // frequency unit glitch
        check(XyPiTracker.normFreq(987, 9870) == 9870, "987 -> 9870");
        check(XyPiTracker.normFreq(1060, 10600) == 10600 && XyPiTracker.normFreq(922, 9220) == 9220, "1060/922 scaled");
        check(XyPiTracker.normFreq(9870, 9870) == 9870, "good value unchanged");
        check(XyPiTracker.normFreq(500, 9870) == 9870, "odd small value held");
        check(XyPiTracker.normFreq(987, -1) == 987, "no FM history: unchanged (AM)");
        XyPiTracker g = tracker();
        int last = 9870; int tunes = 0;
        int[] seq = {9870, 987, 9870, 9870, 9220, 922, 9220, 10600, 1060, 10600};
        int prev = -1;
        for (int raw : seq) {
            int f = XyPiTracker.normFreq(raw, last);
            if (f >= 8750) last = f;
            if (g.onFreq(1000 + tunes, f)) tunes++;
            prev = f;
        }
        check(tunes == 3, "glitchy units give only the 3 real tunes (initial + 2 changes), got " + tunes);
        // D. AF freshness
        XyPiTracker z = tracker();
        int[] la = {10600}, lb = {9870, 8950};
        z.onFreq(0, 9870);
        z.onRead(0, 9870, -1, 0, 25);
        for (int k = 1; k <= 8; k++) { z.noteRawAf(la); z.onRead(k * 500L, 9870, 0x20FE, 0, 25); }
        check(z.afFresh(4000, la, 0, 25), "normal list fresh");
        z.onFreq(5000, 10600);
        check(!z.afFresh(5500, lb, 0, 25), "no AF inside the 2.5 s blanking");
        check(z.afFresh(8000, lb, 0, 25), "different list after blanking is fresh");
        check(z.afFresh(8000, lb, 30, 25), "AF taken at bler 30");
        check(!z.afFresh(8000, lb, 40, 25), "no AF at bler >= 40");
        check(!z.afFresh(8000, lb, 0, 6), "no AF at a dead signal");
        check(!z.afFresh(8000, la, 0, 25), "unchanged leftover list is not fresh without evidence");
        z.onRead(8000, 10600, 0x20FE, 5, 25);     // bler > 0 after settle
        check(z.afFresh(8100, la, 0, 25), "leftover-equal list accepted once bler>0 is seen");
    }
}
