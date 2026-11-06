#!/usr/bin/env python3
"""Rebuild out/FMRadio-v8.apk from input/FMRadio-orig.apk (your unit's stock radio) + this folder.
Only classes.dex is replaced, a native lib is added and some PNGs are recoloured; every other zip entry
is copied byte-for-byte from the original. See README.md for the environment variables."""
import os, re, shutil, subprocess, sys, zipfile, tempfile, glob
H = os.path.dirname(os.path.abspath(__file__))
WIN = os.name == "nt"
def need(path, hint):
    if not path or not os.path.exists(path):
        sys.exit("missing: %s\n  %s" % (path, hint))
    return path
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
def newest(pattern):
    m = sorted(glob.glob(pattern)); return m[-1] if m else ""
APKTOOL = need(os.environ.get("APKTOOL_JAR", os.path.join(H, "tools", "apktool.jar")),
               "download apktool.jar (https://apktool.org) to tools/apktool.jar or set APKTOOL_JAR")
BT = need(os.environ.get("BUILD_TOOLS") or newest(os.path.join(SDK, "build-tools", "*")),
          "set ANDROID_HOME (SDK with build-tools) or BUILD_TOOLS")
PLATFORM = need(os.environ.get("ANDROID_JAR") or newest(os.path.join(SDK, "platforms", "android-*", "android.jar")),
                "install an SDK platform or set ANDROID_JAR")
NDK = need(os.environ.get("NDK_HOME") or newest(os.path.join(SDK, "ndk", "*")), "install the NDK or set NDK_HOME")
ORIG = need(os.path.join(H, "input", "FMRadio-orig.apk"),
            "adb pull /system/priv-app/FMRadio/FMRadio.apk input/FMRadio-orig.apk")
KEYS = os.path.join(H, "keys")
need(os.path.join(KEYS, "platform.pk8"), "put the AOSP platform test key in keys/ (see README.md)")
OUT = os.path.join(H, "out", "FMRadio-v8.apk")
def tool(name):        # build-tools wrappers are .bat on Windows
    return os.path.join(BT, name + (".bat" if WIN and name in ("d8", "apksigner") else ".exe" if WIN else ""))
def run(*a):
    print("+", " ".join(a)); subprocess.check_call(a, shell=(a[0].endswith((".bat", ".cmd"))))
work = tempfile.mkdtemp(prefix="fmpatch-")
dec = os.path.join(work, "dec")
run("java", "-jar", APKTOOL, "d", "-r", "-f", "-o", dec, ORIG)
# --- XyMediaSession: javac (against stubs of FmService/FmStation) -> d8 -> apktool baksmali -> smali tree
cls = os.path.join(work, "cls"); os.makedirs(cls)
srcs = [os.path.join(dp, f) for d in ("src", "stubs") for dp, _, fs in os.walk(os.path.join(H, d)) for f in fs if f.endswith(".java")]
run("javac", "--release", "8", "-Xlint:-options", "-cp", PLATFORM, "-d", cls, *srcs)
# pure-logic self-checks (JVM, no Android): store, PI tracker, AF switch state machine
chk = os.path.join(work, "chk"); os.makedirs(chk)
fm = os.path.join(H, "src", "com", "android", "fmradio")
run("javac", "--release", "8", "-Xlint:-options", "-d", chk,
    *[os.path.join(fm, n) for n in ("XyAfStore.java", "XyPiTracker.java", "XyAfSwitch.java")],
    os.path.join(H, "test", "XyAfStoreCheck.java"), os.path.join(H, "test", "XyAfSwitchCheck.java"))
run("java", "-cp", chk, "XyAfStoreCheck")
run("java", "-cp", chk, "XyAfSwitchCheck")
jar = os.path.join(work, "xy.jar")
with zipfile.ZipFile(jar, "w") as z:      # only the real class(es), never the stubs
    for f in os.listdir(os.path.join(cls, "com", "android", "fmradio")):
        if f.startswith(("XyMediaSession", "XyPi", "XyAf")):
            z.write(os.path.join(cls, "com", "android", "fmradio", f), "com/android/fmradio/" + f)
dexdir = os.path.join(work, "xydex"); os.makedirs(dexdir)
run(tool("d8"), "--min-api", "24", "--lib", PLATFORM, "--output", dexdir, jar)
tmpapk = os.path.join(work, "xy.apk")
with zipfile.ZipFile(tmpapk, "w") as z, zipfile.ZipFile(ORIG) as zo:
    z.writestr("AndroidManifest.xml", zo.read("AndroidManifest.xml"))
    z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")
xysm = os.path.join(work, "xysmali")
run("java", "-jar", APKTOOL, "d", "-r", "-f", "-o", xysm, tmpapk)
dst = os.path.join(dec, "smali", "com", "android", "fmradio")
for f in os.listdir(os.path.join(xysm, "smali", "com", "android", "fmradio")):
    shutil.copy(os.path.join(xysm, "smali", "com", "android", "fmradio", f), dst)
run(sys.executable, "-I", os.path.join(H, "patch", "apply_patch.py"), dec)
# --- v3 colours in code (smali), from v3/colormap.json
sys.path.insert(0, os.path.join(H, "v3")); import recolor, json
cfg = recolor.CFG; nsub = 0
for dp, _, fs in os.walk(os.path.join(dec, "smali")):
    for f in fs:
        p = os.path.join(dp, f); s = open(p, encoding="utf-8", newline="").read(); o = s
        for a, b in cfg["code_strings"].items(): s = s.replace('"%s"' % a, '"%s"' % b)
        for a, b in cfg["code_ints"].items():
            sa = int(a, 16) - 0x100000000; sb = int(b, 16) - 0x100000000
            s = re.sub(r", -0x%x(?=\r?\n)" % -sa, ", -0x%x" % -sb, s)
        if s != o: open(p, "w", encoding="utf-8", newline="").write(s); nsub += 1; print("smali colours:", f)
run("java", "-jar", APKTOOL, "b", "-f", "-o", os.path.join(work, "built.apk"), dec)
newdex = zipfile.ZipFile(os.path.join(work, "built.apk")).read("classes.dex")
unsigned = os.path.join(work, "unsigned.apk")
changes, _names = recolor.compute(ORIG)
clang = newest(os.path.join(NDK, "toolchains", "llvm", "prebuilt", "*", "bin", "armv7a-linux-androideabi24-clang" + (".cmd" if WIN else "")))
solib = os.path.join(work, "libxypi.so")
run(clang, "-shared", "-fPIC", "-O2", "-s", "-Wl,-z,max-page-size=4096", "-Wl,--build-id=none",
    "-o", solib, os.path.join(H, "native", "xypi.c"), "-llog", "-ldl")
EXTRA = {"lib/armeabi-v7a/libxypi.so": open(solib, "rb").read()}
with zipfile.ZipFile(ORIG) as zi, zipfile.ZipFile(unsigned, "w") as zo:
    for info in zi.infolist():
        data = newdex if info.filename == "classes.dex" else changes.get(info.filename, None)
        if data is None: data = zi.read(info.filename)
        zo.writestr(info, data, compress_type=info.compress_type)
    for name, blob in EXTRA.items():     # new entries: stored (uncompressed) so zipalign -p can page-align them
        zi2 = zipfile.ZipInfo(name, (1981, 1, 1, 1, 1, 0)); zi2.compress_type = zipfile.ZIP_STORED
        zo.writestr(zi2, blob)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
aligned = os.path.join(work, "aligned.apk")
run(tool("zipalign"), "-f", "-p", "4", unsigned, aligned)
run(tool("apksigner"), "sign", "--key", os.path.join(KEYS, "platform.pk8"),
    "--cert", os.path.join(KEYS, "platform.x509.pem"), "--v1-signing-enabled", "false",
    "--v2-signing-enabled", "true", "--v3-signing-enabled", "false", "--out", OUT, aligned)
run(tool("apksigner"), "verify", "-v", "--print-certs", OUT)
shutil.rmtree(work, ignore_errors=True)
print("built", OUT)
