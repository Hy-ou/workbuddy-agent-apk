#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* WorkBuddy node shim: 依据 argv[0] 决定运行哪个 JS 入口。
 * 路径来自环境变量（App 数据目录不可 exec，故本文件位于 jniLibs）。 */
static int exists(const char *p) { return access(p, R_OK) == 0; }

int main(int argc, char **argv) {
    const char *self = (argc > 0 && argv[0]) ? argv[0] : "";
    const char *base = strrchr(self, '/');
    base = base ? base + 1 : self;

    const char *home = getenv("WB_DSH_HOME");
    const char *node = getenv("WB_NODE");
    if (!home || !node) { fprintf(stderr, "WB_DSH_HOME/WB_NODE not set\n"); return 127; }

    char script[4096];
    script[0] = 0;
    if (strstr(base, "pnpm")) {
        snprintf(script, sizeof(script), "%s/pnpmjs/node_modules/pnpm/bin/pnpm.mjs", home);
        if (!exists(script)) snprintf(script, sizeof(script), "%s/pnpm/bin/pnpm.cjs", home);
    } else if (strstr(base, "npm")) {
        snprintf(script, sizeof(script), "%s/npm/bin/npm-cli.js", home);
    } else if (strstr(base, "node")) {
        execv(node, argv);
        perror("execv node");
        return 127;
    } else {
        fprintf(stderr, "unknown shim: %s\n", base);
        return 127;
    }
    if (!exists(script)) { fprintf(stderr, "shim entry missing: %s\n", script); return 127; }

    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) return 127;
    args[0] = (char *)node;
    args[1] = script;
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    execv(node, args);
    perror("execv shim");
    return 127;
}