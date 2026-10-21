#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>

/* WorkBuddy 启动器（位于 jniLibs，nativeLibraryDir 允许 exec）。
 *
 * 背景：Android 10+ 的 SELinux 不允许应用进程执行应用私有目录里的文件
 * （exec 返回 EACCES），所以下载到 files/ 下的工具链二进制不能直接跑。
 * 做法：
 *   home/bin/<name>  ->  本文件（符号链接）
 *   本文件按 argv[0] 找到真正的目标，再用 /system/bin/linker64 加载执行
 *   （linker64 会以 mmap 方式加载目标，不触发对目标文件的 exec 检查）。
 *
 * node/npm/pnpm 走另外的分支（它们是 JS，由 node 解释执行）。
 */
static int exists(const char *p) { return access(p, R_OK) == 0; }

static const char *LINKER = "/system/bin/linker64";
static const char *LINKER32 = "/system/bin/linker";

static void exec_linker(const char *target, char **argv) {
    int argc = 0;
    while (argv[argc]) argc++;
    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) exit(127);
    args[0] = (char *)LINKER;
    args[1] = (char *)target;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    execv(LINKER, args);
    perror("execv linker64");
    /* 32 位设备回退 */
    args[0] = (char *)LINKER32;
    execv(LINKER32, args);
    perror("execv linker");
    exit(127);
}

/* Android 没有 bash：把 bash/sh 落到 /system/bin/sh（mksh）。
 * 官方 bash 工具执行的是 spawn("bash", "-c", ...)，PATH 首位是 home/bin，
 * 里面的 bash 符号链接指向本启动器，于是命令能跑起来（只支持 POSIX 语法）。 */
static int exec_system_sh(char **argv) {
    int argc = 0;
    while (argv[argc]) argc++;
    char **args = calloc((size_t)argc + 1, sizeof(char *));
    if (!args) return 127;
    args[0] = (char *)"/system/bin/sh";
    for (int i = 1; i < argc; i++) args[i] = argv[i];
    args[argc] = NULL;
    execv("/system/bin/sh", args);
    perror("execv /system/bin/sh");
    return 127;
}

int main(int argc, char **argv) {
    const char *self = (argc > 0 && argv[0]) ? argv[0] : "";
    const char *base = strrchr(self, '/');
    base = base ? base + 1 : self;

    const char *home = getenv("WB_DSH_HOME");
    const char *node = getenv("WB_NODE");
    /* home 只有 node/npm/pnpm 分支需要；工具链分支靠 WB_TOOLCHAIN_BIN */

    char script[4096];


    /* ---- node / npm / pnpm：JS 入口 ---- */
    if (!strcmp(base, "node")) {
        if (!node) { fprintf(stderr, "WB_NODE not set\n"); return 127; }
        execv(node, argv);
        perror("execv node");
        return 127;
    }
    if (!strcmp(base, "npm") || !strcmp(base, "npx")) {
        if (!node) return 127;
        snprintf(script, sizeof(script), "%s/npm/bin/%s-cli.js", home, strcmp(base, "npm") ? "npx" : "npm");
        if (!exists(script)) { snprintf(script, sizeof(script), "%s/npm/bin/npm-cli.js", home); }
        char **args = calloc((size_t)argc + 2, sizeof(char *));
        args[0] = (char *)node; args[1] = script;
        for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
        execv(node, args);
        return 127;
    }
    if (!strcmp(base, "pnpm") || !strcmp(base, "pnpx")) {
        if (!node) return 127;
        snprintf(script, sizeof(script), "%s/pnpm10/bin/pnpm.cjs", home);
        if (!exists(script))
            snprintf(script, sizeof(script), "%s/pnpmjs/node_modules/pnpm/bin/pnpm.cjs", home);
        if (!exists(script)) return 127;
        char **args = calloc((size_t)argc + 2, sizeof(char *));
        args[0] = (char *)node; args[1] = script;
        for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
        execv(node, args);
        return 127;
    }

    /* ---- 工具链二进制 / 脚本 ---- */
    char target[4096];
    const char *env1 = getenv("WB_TOOLCHAIN_BIN");
    const char *env2 = getenv("WB_TOOLS_BIN");
    target[0] = 0;
    if (env1) { snprintf(target, sizeof(target), "%s/%s", env1, base); if (!exists(target)) target[0] = 0; }
    if (!target[0] && env2) { snprintf(target, sizeof(target), "%s/%s", env2, base); if (!exists(target)) target[0] = 0; }
    if (!target[0]) {
        if (!strcmp(base, "bash") || !strcmp(base, "sh")) return exec_system_sh(argv);
        fprintf(stderr, "wbexec: target not found for %s\n", base);
        return 127;
    }

    /* 判断是 ELF 还是脚本 */
    int fd = open(target, O_RDONLY);
    if (fd < 0) { perror("open target"); return 127; }
    char hdr[256];
    ssize_t n = read(fd, hdr, sizeof(hdr) - 1);
    close(fd);
    if (n <= 0) return 127;
    hdr[n] = 0;

    if (n >= 4 && (unsigned char)hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
        exec_linker(target, argv);
        return 127;
    }

    if (hdr[0] == '#' && hdr[1] == '!') {
        /* 解析 shebang：取解释器，并把 Termux 前缀换成我们的前缀 */
        char line[256];
        int i = 2, j = 0;
        while (hdr[i] == ' ') i++;
        while (hdr[i] && hdr[i] != '\n' && hdr[i] != '\r' && j < 200) line[j++] = hdr[i++];
        line[j] = 0;
        char *interp = line;
        char *arg0 = NULL;
        for (int k = 0; line[k]; k++) {
            if (line[k] == ' ') { line[k] = 0; arg0 = line + k + 1; while (*arg0 == ' ') arg0++; break; }
        }
        /* env 形式：#!/usr/bin/env python3 */
        if (strstr(interp, "/env")) {
            if (!arg0 || !*arg0) { fprintf(stderr, "wbexec: env shebang without program\n"); return 127; }
            interp = arg0;
            arg0 = NULL;
        } else {
            const char *termux = "/data/data/com.termux/files/usr/bin/";
            if (!strncmp(interp, termux, strlen(termux))) {
                static char mapped[4096];
                snprintf(mapped, sizeof(mapped), "%s/%s", env1 ? env1 : "", interp + strlen(termux));
                interp = mapped;
            } else if (interp[0] == '/' && !exists(interp)) {
                const char *b = strrchr(interp, '/');
                static char mapped2[4096];
                snprintf(mapped2, sizeof(mapped2), "%s/%s", env1 ? env1 : "", b ? b + 1 : interp);
                if (exists(mapped2)) interp = mapped2;
                /* #!/bin/bash、#!/usr/bin/env bash 在 Android 上并不存在 -> 用系统 shell */
                else if (b && (!strcmp(b + 1, "bash") || !strcmp(b + 1, "sh"))) interp = (char *)"/system/bin/sh";
            }
        }
        /* 解释器同样需要 linker64（它是 ELF） */
        int cnt = 0;
        while (argv[cnt]) cnt++;
        char **args = calloc((size_t)cnt + 4, sizeof(char *));
        if (!args) return 127;
        int k = 0;
        args[k++] = (char *)interp;
        if (arg0 && *arg0) args[k++] = arg0;
        args[k++] = target;
        for (int m = 1; m < cnt; m++) args[k++] = argv[m];
        args[k] = NULL;
        /* 系统 shell 可以直接 exec；工具链里的解释器是 ELF，需要 linker64 加载 */
        if (!strcmp(interp, "/system/bin/sh")) {
            execv(interp, args);
            perror("execv /system/bin/sh for script");
            return 127;
        }
        char **full = calloc((size_t)k + 2, sizeof(char *));
        if (!full) return 127;
        full[0] = (char *)LINKER;
        full[1] = args[0];
        for (int m = 1; m < k; m++) full[m + 1] = args[m];
        execv(LINKER, full);
        perror("execv linker for script");
        return 127;
    }

    fprintf(stderr, "wbexec: %s is neither ELF nor script\n", base);
    return 127;
}
