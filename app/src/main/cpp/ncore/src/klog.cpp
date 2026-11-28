// SPDX-License-Identifier: GPL-3.0-or-later
#include "klog.h"

#include <cstdarg>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>

namespace ncore {

void klog(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;

    static int kmsg_fd = -2;
    if (kmsg_fd == -2) {
        kmsg_fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    }
    if (kmsg_fd >= 0) {
        // 内核要求每行带优先级前缀；"6" = KLOG_INFO。
        std::string line = "6<NekoSU> ";
        line += buf;
        if (line.back() != '\n') line += '\n';
        (void)write(kmsg_fd, line.data(), line.size());
    }
}

}  // namespace ncore
