// Watchtower NDK crash handler (SDK-003). On SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL or SIGTRAP
// it writes a small text report (signal, code, fault address and up to 64 frames as module and
// offset) to a file set at install time, then hands the signal to the previous handler, so the
// system tombstone and any other crash reporter still run. The next launch reads the file and
// sends one error event (dev.watchtower.NativeCrash). Only async-signal-safe calls write the file;
// the unwinder and dladdr are what native crash reporters use in practice, but are not
// guaranteed safe, so the report is written before the stack walk and stays useful without it.
// Each frame is followed by the module's GNU build id (`bid=`), which keys the symbol file on the
// server. After the crashing thread, every other thread is asked (tgkill with a real-time signal)
// to write its own frames under a `thread=<tid>` line, within a short time budget.
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define MAX_FRAMES 64
#define MAX_THREADS 16
#define THREAD_FRAMES 28 /* the first two or three are the dump handler itself */
#define DUMP_SIGNAL (SIGRTMIN + 2)
#define DUMP_WAIT_BUDGET 150 /* 2 ms steps over all threads: 300 ms at most */
#define PATH_MAX_LEN 512

static const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
#define NSIG_HANDLED (sizeof(kSignals) / sizeof(kSignals[0]))

static char g_path[PATH_MAX_LEN];
static struct sigaction g_old[NSIG_HANDLED];
static volatile sig_atomic_t g_in_handler = 0;
static char g_altstack[64 * 1024];

static void put(int fd, const char *s) {
    size_t n = strlen(s);
    while (n > 0) {
        ssize_t w = write(fd, s, n);
        if (w <= 0) return;
        s += w;
        n -= (size_t)w;
    }
}

static void put_hex(int fd, uintptr_t v) {
    char buf[2 + 2 * sizeof(uintptr_t) + 1];
    char *p = buf + sizeof(buf) - 1;
    *p = '\0';
    do {
        *--p = "0123456789abcdef"[v & 0xf];
        v >>= 4;
    } while (v != 0);
    *--p = 'x';
    *--p = '0';
    put(fd, p);
}

static void put_dec(int fd, long v) {
    char buf[24];
    char *p = buf + sizeof(buf) - 1;
    int neg = v < 0;
    unsigned long u = neg ? (unsigned long)(-(v + 1)) + 1 : (unsigned long)v;
    *p = '\0';
    do {
        *--p = (char)('0' + (u % 10));
        u /= 10;
    } while (u != 0);
    if (neg) *--p = '-';
    put(fd, p);
}

struct walk {
    int fd;
    int n;
    int max;
};

/* The GNU build id note of the ELF image loaded at `base`, as `bid=<hex>`; nothing if it has none. */
static void put_build_id(int fd, uintptr_t base) {
    const ElfW(Ehdr) *eh = (const ElfW(Ehdr) *)base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 || eh->e_phoff == 0) return;
    const ElfW(Phdr) *ph = (const ElfW(Phdr) *)(base + eh->e_phoff);
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type != PT_NOTE) continue;
        const char *p = (const char *)(base + ph[i].p_vaddr);
        const char *end = p + ph[i].p_memsz;
        while (p + sizeof(ElfW(Nhdr)) <= end) {
            const ElfW(Nhdr) *n = (const ElfW(Nhdr) *)p;
            const char *name = p + sizeof(*n);
            const char *desc = name + ((n->n_namesz + 3) & ~3u);
            if (desc + n->n_descsz > end) return;
            if (n->n_type == NT_GNU_BUILD_ID && n->n_namesz == 4 && memcmp(name, "GNU", 4) == 0) {
                put(fd, "bid=");
                for (unsigned j = 0; j < n->n_descsz; j++) {
                    char two[3] = {"0123456789abcdef"[((unsigned char)desc[j]) >> 4],
                                   "0123456789abcdef"[((unsigned char)desc[j]) & 0xf], 0};
                    put(fd, two);
                }
                put(fd, "\n");
                return;
            }
            p = desc + ((n->n_descsz + 3) & ~3u);
        }
    }
}

static _Unwind_Reason_Code frame(struct _Unwind_Context *ctx, void *arg) {
    struct walk *w = (struct walk *)arg;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(ctx);
    if (pc == 0) return _URC_NO_REASON;
    if (w->n >= w->max) return _URC_END_OF_STACK;
    Dl_info info;
    uintptr_t fbase = 0;
    put(w->fd, "frame=");
    put_hex(w->fd, pc);
    if (dladdr((void *)pc, &info) != 0 && info.dli_fname != NULL) {
        fbase = (uintptr_t)info.dli_fbase;
        put(w->fd, " ");
        put(w->fd, info.dli_fname);
        put(w->fd, " ");
        put_hex(w->fd, pc - (uintptr_t)info.dli_fbase);
        if (info.dli_sname != NULL) {
            put(w->fd, " ");
            put(w->fd, info.dli_sname);
        }
    }
    put(w->fd, "\n");
    if (fbase != 0) put_build_id(w->fd, fbase);
    w->n++;
    return _URC_NO_REASON;
}

static int index_of(int sig) {
    for (size_t i = 0; i < NSIG_HANDLED; i++) {
        if (kSignals[i] == sig) return (int)i;
    }
    return -1;
}

struct wt_dirent64 {
    uint64_t d_ino;
    int64_t d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
};

static volatile int g_dump_fd = -1;
static volatile int g_dump_done = 0;

/* Runs on the thread asked to dump itself (DUMP_SIGNAL). */
static void thread_handler(int sig, siginfo_t *info, void *uctx) {
    (void)sig;
    (void)info;
    (void)uctx;
    int fd = g_dump_fd;
    if (fd < 0) return;
    put(fd, "thread=");
    put_dec(fd, (long)syscall(SYS_gettid));
    put(fd, "\n");
    struct walk w = {fd, 0, THREAD_FRAMES};
    _Unwind_Backtrace(frame, &w);
    __atomic_store_n(&g_dump_done, 1, __ATOMIC_RELEASE);
}

/* Asks each other thread, one at a time, to write its frames; the crash itself is already in the file. */
static void dump_other_threads(int fd) {
    char buf[1024] __attribute__((aligned(8)));
    int dirfd = open("/proc/self/task", O_RDONLY | O_CLOEXEC);
    if (dirfd < 0) return;
    long self = (long)syscall(SYS_gettid);
    long pid = (long)getpid();
    int dumped = 0;
    int budget = DUMP_WAIT_BUDGET;
    long nread;
    g_dump_fd = fd;
    while (dumped < MAX_THREADS && budget > 0 && (nread = syscall(SYS_getdents64, dirfd, buf, sizeof(buf))) > 0) {
        for (long off = 0; off < nread && dumped < MAX_THREADS && budget > 0;) {
            struct wt_dirent64 *d = (struct wt_dirent64 *)(buf + off);
            off += d->d_reclen;
            long tid = 0;
            for (const char *c = d->d_name; *c >= '0' && *c <= '9'; c++) tid = tid * 10 + (*c - '0');
            if (tid <= 0 || tid == self) continue;
            __atomic_store_n(&g_dump_done, 0, __ATOMIC_RELEASE);
            if (syscall(SYS_tgkill, pid, tid, DUMP_SIGNAL) != 0) continue;
            while (budget > 0 && !__atomic_load_n(&g_dump_done, __ATOMIC_ACQUIRE)) {
                struct timespec ts = {0, 2000000};
                nanosleep(&ts, NULL);
                budget--;
            }
            dumped++;
        }
    }
    g_dump_fd = -1;
    close(dirfd);
}

static void handler(int sig, siginfo_t *info, void *uctx) {
    int i = index_of(sig);
    if (!g_in_handler && g_path[0] != '\0') {
        g_in_handler = 1;
        int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            put(fd, "signal=");
            put_dec(fd, sig);
            put(fd, "\ncode=");
            put_dec(fd, info != NULL ? info->si_code : 0);
            put(fd, "\naddr=");
            put_hex(fd, info != NULL ? (uintptr_t)info->si_addr : 0);
            put(fd, "\n");
            struct walk w = {fd, 0, MAX_FRAMES};
            _Unwind_Backtrace(frame, &w);
            dump_other_threads(fd);
            close(fd);
        }
    }
    // hand over: restore the previous action and re-raise (or call it directly)
    if (i >= 0) {
        struct sigaction *old = &g_old[i];
        if (old->sa_flags & SA_SIGINFO) {
            if (old->sa_sigaction != NULL) {
                old->sa_sigaction(sig, info, uctx);
                return;
            }
        } else if (old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN && old->sa_handler != NULL) {
            old->sa_handler(sig);
            return;
        }
        sigaction(sig, old, NULL);
    } else {
        signal(sig, SIG_DFL);
    }
    raise(sig);
}

JNIEXPORT void JNICALL Java_dev_watchtower_NativeCrash_nativeInstall(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return;
    size_t n = strlen(p);
    if (n == 0 || n >= PATH_MAX_LEN) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return;
    }
    memcpy(g_path, p, n + 1);
    (*env)->ReleaseStringUTFChars(env, path, p);

    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_altstack;
    ss.ss_size = sizeof(g_altstack);
    sigaltstack(&ss, NULL); // a stack overflow on this (the main) thread still gets a report

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (size_t i = 0; i < NSIG_HANDLED; i++) {
        sigaction(kSignals[i], &sa, &g_old[i]);
    }
    struct sigaction ta;
    memset(&ta, 0, sizeof(ta));
    sigemptyset(&ta.sa_mask);
    ta.sa_sigaction = thread_handler;
    ta.sa_flags = SA_SIGINFO | SA_RESTART;
    sigaction(DUMP_SIGNAL, &ta, NULL);
}
