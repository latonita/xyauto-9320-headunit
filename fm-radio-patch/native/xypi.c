// libxypi: read cached RDS data (PI, AF list) out of the radio's already-loaded libfmjni. Read-only.
//
// libfmjni keeps one `struct fmr_ds` (exported symbol `fmr_data`, also pfmr_data[0]); its RDSData_Struct
// is at +0xe0 (FMR_get_pi passes fmr_data+0xe0 to COM_get_pi). RDSData_Struct matches AOSP MediaTek fm.h:
//   +0x18 u16 PI | +0x1c s16 AF_Data.AF_Num (<=25) | +0x50 s16 AF_Data.AF[1][25] (the list COM_active_af walks)
//   +0x83 u8 isMethod_A | +0x84 u8 isAFNum_Get | +0x22a u16 event_status
// Everything is SIGSEGV-guarded and the struct is only trusted after a PI cross-check against FMR_get_pi.
#include <jni.h>
#include <dlfcn.h>
#include <setjmp.h>
#include <signal.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <android/log.h>

#define TAG "XyPi"
typedef int (*get_pi_fn)(int, unsigned short *);
static sigjmp_buf g_jb;
static volatile sig_atomic_t g_armed;
static void on_segv(int sig) { if (g_armed) siglongjmp(g_jb, 1); signal(sig, SIG_DFL); raise(sig); }

static void *g_lib;
static get_pi_fn resolve(void) {
    static get_pi_fn fn;
    if (fn) return fn;
    void *h = dlopen("libfmjni.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen("libfmjni.so", RTLD_NOW);
    if (!h) { __android_log_print(ANDROID_LOG_WARN, TAG, "libfmjni not loadable"); return 0; }
    g_lib = h;
    fn = (get_pi_fn)dlsym(h, "_Z10FMR_get_piiPt");
    if (!fn) __android_log_print(ANDROID_LOG_WARN, TAG, "FMR_get_pi not found");
    return fn;
}

#define GUARD_BEGIN struct sigaction sa, old; memset(&sa, 0, sizeof sa); sa.sa_handler = on_segv; \
    sigemptyset(&sa.sa_mask); sigaction(SIGSEGV, &sa, &old); if (sigsetjmp(g_jb, 1) == 0) { g_armed = 1;
#define GUARD_END } else { fault = 1; } g_armed = 0; sigaction(SIGSEGV, &old, 0);

JNIEXPORT jint JNICALL Java_com_android_fmradio_XyPi_nativeGetPi(JNIEnv *env, jclass cls) {
    get_pi_fn fn = resolve();
    if (!fn) return -1;
    unsigned short pi = 0xFFFF; int rc = -1, fault = 0;
    GUARD_BEGIN rc = fn(0, &pi); GUARD_END
    if (fault || rc != 0 || pi == 0xFFFF || pi == 0) return -1;      // 0 = cleared by nativeClearRds, never a real PI
    return pi;
}

static int norm_af(int v) {
    if (v >= 8750 && v <= 10800) return v;            // already 10 kHz units
    if (v >= 875 && v <= 1080) return v * 10;         // 100 kHz units
    if (v >= 1 && v <= 204) return 8750 + v * 10;     // raw AF code: 87.5 MHz + n * 0.1 MHz
    return -1;
}

static int g_rds_off = -1;   // offset of RDSData_Struct inside fmr_data, found and cached once validated

JNIEXPORT jintArray JNICALL Java_com_android_fmradio_XyPi_nativeGetAf(JNIEnv *env, jclass cls) {
    jint out[25]; int n = 0, fault = 0;
    get_pi_fn fn = resolve();
    if (fn && g_lib) {
        void **ppd = (void **)dlsym(g_lib, "pfmr_data");
        char *fd = (char *)dlsym(g_lib, "fmr_data");
        GUARD_BEGIN
            unsigned short pi = 0xFFFF;
            char *base = (ppd && ppd[0]) ? (char *)ppd[0] : fd;
            if (base && fn(0, &pi) == 0 && pi != 0xFFFF && pi != 0) {
                int offs[1 + 0x200 / 2]; int no = 0;
                if (g_rds_off >= 0) offs[no++] = g_rds_off;
                else { offs[no++] = 0xe0; for (int o = 0x40; o <= 0x200; o += 2) if (o != 0xe0) offs[no++] = o; }
                for (int i = 0; i < no; i++) {
                    char *r = base + offs[i];
                    if (*(uint16_t *)(r + 0x18) != pi) continue;           // PI cross-check
                    if (!(*(uint16_t *)(r + 0x22a) & 2)) continue;         // RDS_EVENT_PI valid
                    int16_t cnt = *(int16_t *)(r + 0x1c);
                    if (cnt < 0 || cnt > 25) continue;
                    if (g_rds_off != offs[i]) {
                        g_rds_off = offs[i];
                        __android_log_print(ANDROID_LOG_INFO, TAG, "RDS struct at fmr_data+0x%x", g_rds_off);
                    }
                    const int16_t *af = (const int16_t *)(r + 0x50);
                    for (int k = 0; k < cnt; k++) {
                        int f = norm_af(af[k]);
                        if (f < 0) continue;
                        int dup = 0; for (int j = 0; j < n; j++) if (out[j] == f) dup = 1;
                        if (!dup) out[n++] = f;
                    }
                    break;
                }
            }
        GUARD_END
    }
    if (fault) n = 0;
    jintArray arr = (*env)->NewIntArray(env, n);
    if (arr && n) (*env)->SetIntArrayRegion(env, arr, 0, n, out);
    return arr;
}

// PAMD level of the current tune: ioctl(fd, 0xC004F50E = FM_IOCTL_GETCURPAMD) on the fd the library itself holds.
// FMR_get_rssi does `COM_get_rssi(*(int*)fmr_data[idx], &v)`, so the fd is the first word of fmr_data.
// Read-only (same request COM_active_af issues). The fd is only used if /proc/self/fd/N points at /dev/fm.
// Returns -1 when unavailable.
JNIEXPORT jint JNICALL Java_com_android_fmradio_XyPi_nativeGetPamd(JNIEnv *env, jclass cls) {
    static int warned;
    if (!resolve() || !g_lib) return -1;
    void **ppd = (void **)dlsym(g_lib, "pfmr_data");
    char *fdp = (char *)dlsym(g_lib, "fmr_data");
    int val = -1, fault = 0, fd = -1;
    GUARD_BEGIN
        char *base = (ppd && ppd[0]) ? (char *)ppd[0] : fdp;
        if (base) fd = *(int *)base;
    GUARD_END
    if (fault || fd < 3 || fd > 4096) return -1;
    char path[64], link[128];
    snprintf(path, sizeof path, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, link, sizeof link - 1);
    if (n <= 0) return -1;
    link[n] = 0;
    if (strcmp(link, "/dev/fm") != 0) {
        if (!warned) { warned = 1; __android_log_print(ANDROID_LOG_WARN, TAG, "fd %d is %s, not /dev/fm: no PAMD", fd, link); }
        return -1;
    }
    unsigned int arg = 0;
    if (ioctl(fd, 0xC004F50E, &arg) != 0) return -1;
    val = (int)arg;
    return val;
}

// Invalidate the cached RDS data after a tune: libfmjni never does (COM_read_rds_data read()s the whole 0x2b0-byte
// RDSData_Struct from /dev/fm and returns -1013 otherwise; COM_get_pi returns the PI at +0x18 only while event_status
// (u16 @ +0x22a) bit 1 is set). Zeroes PI (+0x18) and AF_Num (+0x1c) only (since v8.3 event_status is left alone; PI 0 reads as empty). Offsets verified in the
// disassembly (COM_get_pi, COM_read_rds_data, FMR_active_*: rds = fmr_data+0xe0). PS/RT are left alone (offsets not verified).
// Returns 1 when written, 0 otherwise.
JNIEXPORT jint JNICALL Java_com_android_fmradio_XyPi_nativeClearRds(JNIEnv *env, jclass cls) {
    if (!resolve() || !g_lib) return 0;
    void **ppd = (void **)dlsym(g_lib, "pfmr_data");
    char *fdp = (char *)dlsym(g_lib, "fmr_data");
    int fault = 0, done = 0;
    GUARD_BEGIN
        char *base = (ppd && ppd[0]) ? (char *)ppd[0] : fdp;
        if (base) {
            char *r = base + (g_rds_off >= 0 ? g_rds_off : 0xe0);
            *(uint16_t *)(r + 0x18) = 0;
            *(int16_t *)(r + 0x1c) = 0;
            done = 1;
        }
    GUARD_END
    return fault ? 0 : done;
}
