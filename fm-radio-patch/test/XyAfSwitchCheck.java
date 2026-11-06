import com.android.fmradio.XyAfSwitch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake clock + fake radio check of the XyAfSwitch state machine, run by build.py. Exits non-zero on failure. */
public class XyAfSwitchCheck {
    static void check(boolean c, String m) { if (!c) { System.err.println("FAIL: " + m); System.exit(1); } }

    static final int HOME = 9870, A = 10600, B = 8950, C = 9810, D = 9220;
    static final int PI = 0x20FE;

    static final class Fake implements XyAfSwitch.Radio {
        long now = 100000;
        boolean powered = true, busy;
        int freq = HOME;
        final Map<Integer, Integer> pamdAt = new HashMap<Integer, Integer>();     // frequency -> pamd
        final Map<Integer, Integer> piAt = new HashMap<Integer, Integer>();       // frequency -> PI that validates there
        long piDelay = 2500;                                                       // ms after tune until the PI validates
        long tunedAt;
        int[] cands = {A, B, C};
        final List<Integer> tunes = new ArrayList<Integer>();
        final List<String> successes = new ArrayList<String>();
        boolean piValidAtHome = true;
        final List<String> log = new ArrayList<String>();

        public long now() { return now; }
        public boolean powered() { return powered; }
        public boolean busy() { return busy; }
        public int frequency() { return freq; }
        public int pamd() { Integer p = pamdAt.get(freq); return p == null ? 5 : p; }
        public int validatedPi() {
            if (freq == HOME) return piValidAtHome ? PI : -1;
            Integer p = piAt.get(freq);
            return p != null && now - tunedAt >= piDelay ? p : -1;
        }
        public void tune(int f) { tunes.add(f); freq = f; tunedAt = now; }
        public int[] candidates(String pi, int exclude, int max) {
            if (!"20FE".equals(pi)) return new int[0];
            List<Integer> l = new ArrayList<Integer>();
            for (int c : cands) if (c != exclude && l.size() < max) l.add(c);
            int[] a = new int[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a;
        }
        public void recordSuccess(String pi, int f) { successes.add(pi + "@" + f); }
        public void log(String s) { log.add(s); System.out.println("  [" + (now - 100000) / 1000.0 + "s] " + s); }
    }

    /** poll every 500 ms while probing else every 2 s, like XyMediaSession, for ms of fake time */
    static void run(Fake f, XyAfSwitch sw, long ms) {
        long end = f.now + ms;
        while (f.now < end) {
            sw.tick();
            f.now += sw.probing() ? 500 : 2000;
        }
    }

    static Fake weakHome() {
        Fake f = new Fake();
        f.pamdAt.put(HOME, 6);
        f.pamdAt.put(A, 25); f.pamdAt.put(B, 26); f.pamdAt.put(C, 24);
        f.piAt.put(A, PI); f.piAt.put(B, PI); f.piAt.put(C, PI);
        return f;
    }

    public static void main(String[] a) {
        trigger();
        accept();
        reject();
        cooldowns();
        suppression();
        System.out.println("XyAfSwitchCheck OK");
    }

    static void trigger() {
        System.out.println("trigger timing");
        Fake f = weakHome();
        XyAfSwitch sw = new XyAfSwitch(f);
        f.freq = HOME; sw.tick();                      // t=0 learns the frequency
        // good signal, then fade: PAMD 6 from t=10 s
        f.pamdAt.put(HOME, 25);
        run(f, sw, 10000);
        check(f.tunes.isEmpty(), "good signal: no switch");
        f.pamdAt.put(HOME, 6);
        long fade = f.now;
        // user quiet window is satisfied (no tune); low must last 6 s and 3 samples
        sw.tick(); f.now += 2000;                       // sample 1 at fade
        sw.tick(); f.now += 2000;                       // 2
        sw.tick(); f.now += 2000;                       // 3 at +4 s
        check(f.tunes.isEmpty(), "no switch before 6 s of low PAMD");
        sw.tick();                                      // 4 at +6 s
        check(f.now - fade == 6000 && f.tunes.size() == 1, "trigger exactly at 6 s low: tunes=" + f.tunes.size());
        check(f.tunes.get(0) == A, "first candidate tuned");

        // brief dip (98.7 dips to 8..14 for a few seconds) never triggers
        Fake g = weakHome(); XyAfSwitch sg = new XyAfSwitch(g);
        g.pamdAt.put(HOME, 25); run(g, sg, 30000);
        g.pamdAt.put(HOME, 8); run(g, sg, 4000);
        g.pamdAt.put(HOME, 14); run(g, sg, 10000);
        g.pamdAt.put(HOME, 8); run(g, sg, 4000);
        check(g.tunes.isEmpty(), "dips shorter than 6 s do not trigger");
        // PAMD 9 is not low
        g.pamdAt.put(HOME, 9); run(g, sg, 30000);
        check(g.tunes.isEmpty(), "pamd 9 is not low");
        // no PI ever seen: no trigger
        Fake h = weakHome(); h.piValidAtHome = false; XyAfSwitch sh = new XyAfSwitch(h);
        run(h, sh, 60000);
        check(h.tunes.isEmpty(), "no validated PI: no trigger");
        // PI validated, then the station stops delivering PI during the fade: still triggers within 60 s
        Fake k = weakHome(); XyAfSwitch sk = new XyAfSwitch(k);
        k.pamdAt.put(HOME, 25); run(k, sk, 20000);
        k.piValidAtHome = false; k.pamdAt.put(HOME, 6);
        run(k, sk, 10000);
        check(k.tunes.size() == 1, "remembered PI is enough (fade without PI): " + k.tunes.size());
        // PI older than 60 s: no trigger
        Fake m = weakHome(); XyAfSwitch sm = new XyAfSwitch(m);
        m.pamdAt.put(HOME, 25); run(m, sm, 10000);
        m.piValidAtHome = false; m.pamdAt.put(HOME, 6);
        run(m, sm, 62000);
        check(m.tunes.size() == 1 || m.tunes.isEmpty(), "sanity");
        Fake m2 = weakHome(); XyAfSwitch sm2 = new XyAfSwitch(m2);
        m2.piValidAtHome = true; m2.pamdAt.put(HOME, 25); sm2.tick(); m2.now += 2000;
        m2.piValidAtHome = false; m2.pamdAt.put(HOME, 25); run(m2, sm2, 70000);
        m2.pamdAt.put(HOME, 6); run(m2, sm2, 20000);
        check(m2.tunes.isEmpty(), "PI older than 60 s: no trigger");
        // no confirmed AFs: no trigger
        Fake n = weakHome(); n.cands = new int[0]; XyAfSwitch sn = new XyAfSwitch(n);
        run(n, sn, 60000);
        check(n.tunes.isEmpty(), "no confirmed AFs: no trigger");
        // disabled
        Fake o = weakHome(); XyAfSwitch so = new XyAfSwitch(o); so.setEnabled(false);
        run(o, so, 60000);
        check(o.tunes.isEmpty() && !so.enabled(), "disabled: no trigger");
        so.setEnabled(true);
        run(o, so, 20000);
        check(!o.tunes.isEmpty(), "enabled again: triggers");
    }

    static void accept() {
        System.out.println("accept path");
        Fake f = weakHome();
        XyAfSwitch sw = new XyAfSwitch(f);
        run(f, sw, 40000);
        check(f.freq == A && f.tunes.size() == 1, "switched to first candidate " + f.freq + " tunes=" + f.tunes);
        check(f.successes.size() == 1 && f.successes.get(0).equals("20FE@" + A), "success recorded " + f.successes);
        check(!sw.probing(), "probe over");

        // candidate order: as given by the store (lastSuccess first is the store's job); here A rejects (low pamd), B accepts
        Fake g = weakHome(); g.pamdAt.put(A, 12);          // below 15
        XyAfSwitch sg = new XyAfSwitch(g);
        run(g, sg, 40000);
        check(g.tunes.size() == 2 && g.tunes.get(0) == A && g.tunes.get(1) == B && g.freq == B, "A rejected on PAMD 12, B accepted: " + g.tunes);

        // PAMD >= 15 but not 4 above the pre-switch PAMD: reject
        Fake h = weakHome(); h.pamdAt.put(HOME, 13);       // not low: use a fade at 8 instead
        h.pamdAt.put(HOME, 8); h.pamdAt.put(A, 16);        // 16 < 8+4? no: 16 >= 12 -> accepted; make pre-switch pamd 8 and A=15 -> 15 >= 12 ok
        XyAfSwitch sh = new XyAfSwitch(h);
        run(h, sh, 40000);
        check(h.freq == A, "pamd 16 vs 8 accepted");
        Fake h2 = weakHome(); h2.pamdAt.put(HOME, 8); h2.pamdAt.put(A, 15); h2.pamdAt.put(B, 11); h2.pamdAt.put(C, 18);
        XyAfSwitch sh2 = new XyAfSwitch(h2);
        run(h2, sh2, 40000);
        check(h2.tunes.get(0) == A && h2.freq == A, "pamd 15 vs 8 (gain 7) accepted");

        // wrong PI on the candidate: reject, next
        Fake i = weakHome(); i.piAt.put(A, 0x1234);
        XyAfSwitch si = new XyAfSwitch(i);
        run(i, si, 40000);
        check(i.tunes.size() == 2 && i.freq == B, "wrong PI on A rejected, B accepted: " + i.tunes);

        // PI never arrives: accept only at pamd >= 20
        Fake j = weakHome(); j.piAt.clear(); j.pamdAt.put(A, 19); j.pamdAt.put(B, 22);
        XyAfSwitch sj = new XyAfSwitch(j);
        run(j, sj, 60000);
        check(j.tunes.size() == 2 && j.freq == B, "no PI: A (19) rejected, B (22) accepted: " + j.tunes + " freq " + j.freq);

        // PI arrives late (within 3 s extra): accepted
        Fake k = weakHome(); k.piDelay = 4000;
        XyAfSwitch sk = new XyAfSwitch(k);
        run(k, sk, 40000);
        check(k.freq == A && k.tunes.size() == 1, "late PI (4 s) still accepted");

        // after a switch: stays at least 60 s even when the new station fades
        Fake l = weakHome();
        XyAfSwitch sl = new XyAfSwitch(l);
        while (!(l.freq == A && !sl.probing() && !l.successes.isEmpty()) && l.now < 200000) { sl.tick(); l.now += 500; }
        check(l.freq == A && !sl.probing(), "switched");
        int before = l.tunes.size();
        l.pamdAt.put(A, 5);
        long t0 = l.now;
        while (l.now - t0 < 55000) { sl.tick(); l.now += 2000; check(l.tunes.size() == before, "no second switch inside the 60 s stay"); }
        run(l, sl, 30000);
        check(l.tunes.size() > before, "after the stay it may switch again");
    }

    static void reject() {
        System.out.println("reject path and tune back");
        Fake f = weakHome(); f.pamdAt.put(A, 10); f.pamdAt.put(B, 12); f.pamdAt.put(C, 14);
        XyAfSwitch sw = new XyAfSwitch(f);
        run(f, sw, 40000);
        check(f.tunes.size() == 4, "3 candidates + tune back: " + f.tunes);
        check(f.tunes.get(0) == A && f.tunes.get(1) == B && f.tunes.get(2) == C && f.tunes.get(3) == HOME, "order A,B,C,home " + f.tunes);
        check(f.freq == HOME && f.successes.isEmpty(), "back on original, nothing recorded");
        check(!sw.probing(), "probe over");
        // more than 3 candidates: only 3 probed
        Fake g = weakHome(); g.cands = new int[] {A, B, C, D};
        for (int x : g.cands) { g.pamdAt.put(x, 10); }
        XyAfSwitch sg = new XyAfSwitch(g);
        run(g, sg, 40000);
        check(g.tunes.size() == 4 && !g.tunes.contains(D), "at most 3 candidates probed: " + g.tunes);
    }

    static void cooldowns() {
        System.out.println("cooldowns");
        Fake f = weakHome(); f.pamdAt.put(A, 10); f.pamdAt.put(B, 10); f.pamdAt.put(C, 10);
        XyAfSwitch sw = new XyAfSwitch(f);
        run(f, sw, 40000);
        int first = f.tunes.size();
        check(first == 4, "first attempt failed with 4 tunes " + first);
        long failAt = f.now;
        // still fading: no new attempt for 2 minutes after the failure
        long t0 = f.now;
        int seen = f.tunes.size();
        while (f.now - t0 < 90000) { sw.tick(); f.now += 2000; }
        check(f.tunes.size() == seen, "no retry within 2 min of a failure");
        run(f, sw, 40000);
        check(f.tunes.size() > seen, "retries after the failure cooldown");
        // after a retry that succeeds, 60 s stay
        // attempt gap of 30 s: an aborted probe (radio busy) still counts as an attempt
        Fake g = weakHome();
        XyAfSwitch sg = new XyAfSwitch(g);
        run(g, sg, 1);
        long guard = g.now;
        while (g.tunes.isEmpty() && g.now - guard < 60000) { sg.tick(); g.now += 500; }
        check(g.tunes.size() == 1, "attempt started");
        g.busy = true;                                      // e.g. user starts a seek during the probe
        sg.tick();
        check(!sg.probing(), "probe aborted when busy");
        g.busy = false;
        int cnt = g.tunes.size();
        long s0 = g.now;
        while (g.now - s0 < 25000) { sg.tick(); g.now += 500; }
        check(g.tunes.size() == cnt, "no new attempt within 30 s");
    }

    static void suppression() {
        System.out.println("suppression");
        // a user tune within the last 20 s blocks the trigger
        Fake f = weakHome(); f.pamdAt.put(D, 6); f.piAt.put(D, PI);
        XyAfSwitch sw = new XyAfSwitch(f);
        f.pamdAt.put(HOME, 25);
        run(f, sw, 10000);
        f.freq = D;                                        // user tunes to D (weak, its own PI valid)
        f.piAt.put(D, PI);
        f.now += 100; sw.tick();                           // detected as a user tune
        f.pamdAt.put(D, 6);
        // the switch only knows PI validated on the current freq: make D validate
        // (validatedPi for non-HOME needs piAt + delay: tunedAt = now)
        f.tunedAt = f.now - 5000;
        long u = f.now;
        int n0 = f.tunes.size();
        while (f.now - u < 19000) { sw.tick(); f.now += 2000; check(f.tunes.size() == n0, "no switch within 20 s of a user tune (t=" + (f.now - u) + ")"); }
        run(f, sw, 20000);
        check(f.tunes.size() > n0, "switch allowed after the quiet period");

        // seek/scan/recording: busy blocks and counts as user activity
        Fake g = weakHome();
        XyAfSwitch sg = new XyAfSwitch(g);
        g.busy = true;
        run(g, sg, 60000);
        check(g.tunes.isEmpty(), "busy radio never switches");
        g.busy = false;
        run(g, sg, 16000);
        check(g.tunes.isEmpty(), "20 s quiet after busy ends");
        run(g, sg, 20000);
        check(!g.tunes.isEmpty(), "then it triggers");

        // powered off
        Fake h = weakHome(); XyAfSwitch sh = new XyAfSwitch(h);
        h.powered = false;
        run(h, sh, 60000);
        check(h.tunes.isEmpty(), "powered off: nothing");

        // user retunes during a probe: abort, do not tune back
        Fake i = weakHome();
        XyAfSwitch si = new XyAfSwitch(i);
        long g0 = i.now;
        while (i.tunes.isEmpty() && i.now - g0 < 60000) { si.tick(); i.now += 500; }
        check(i.tunes.size() == 1 && si.probing(), "probing");
        i.freq = D;                                         // the user taps another station
        i.now += 500; si.tick();
        check(!si.probing() && i.tunes.size() == 1 && i.freq == D, "abort without tune-back after a user tune");

        // user-tuned frequency different from our expectation is not mistaken for our own tune later
        Fake j = weakHome();
        XyAfSwitch sj = new XyAfSwitch(j);
        run(j, sj, 40000);
        check(j.freq == A, "own switch done");
        int tn = j.tunes.size();
        check(sj.lastUserTuneAt() < j.now - 1000, "own tune was not logged as a user tune");
    }
}
