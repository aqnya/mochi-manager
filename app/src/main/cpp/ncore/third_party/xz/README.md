# xz（xz-embedded，仅解码）

只用到 **decompress** 部分，用于解压 zip 的 method 95（XZ）。
不走系统的 `liblzma.so`：NDK 不导出这个库，设备上也不保证存在，
静态编进来最稳。

- 来源：tukaani-project/xz-embedded（master），即内核 `lib/xz` 的独立版
- 拉取方式：

```sh
curl -L https://codeload.github.com/tukaani-project/xz-embedded/tar.gz/refs/heads/master | tar xz
# 需要的文件：linux/include/linux/xz.h、linux/lib/xz/{xz_private.h,xz_stream.h,
# xz_lzma2.h,xz_dec_stream.c,xz_dec_lzma2.c,xz_dec_bcj.c,xz_crc32.c,xz_crc64.c}
```

- 许可证：0BSD（见 `LICENSE`），比 BSD 还宽松，不需要署名
- 相对上游没改任何一行代码，配置全在 `xz_config.h`（对应上游
  `userspace/xz_config.h`，裁剪后写死）：
  - `XZ_DEC_DYNALLOC`：只用多调用 + 按需分配字典的模式，输出大小未知也能解
  - `XZ_USE_CRC64` + `XZ_DEC_ANY_CHECK`：CRC64 会校验，其它校验类型（SHA-256
    等）当作不需要校验，省掉 `xz_sha256.c`
  - BCJ 过滤器全开（x86 / ARM / ARMThumb / ARM64 / RISC-V / PowerPC / IA-64 / SPARC）

## 两个必须知道的坑

1. **CRC 表要显式初始化**：`xz_crc32_init()` / `xz_crc64_init()` 必须在第一次
   `xz_dec_run()` 之前调用，否则表全是 0，连 12 字节的 stream header 都过不了，
   报 `XZ_DATA_ERROR`。`unzip.cpp` 里用函数内 `static` 只跑一次。
2. `XZ_USE_CRC64` 既是编译开关也是**声明开关**：`xz.h` 只在它被定义时才声明
   `xz_crc64_init()`。C 源文件从 `xz_config.h` 拿到这个宏，C++ 侧（`unzip.cpp`）
   得自己在 `#include "xz.h"` 前补一行，否则链接前就编译不过。

`unzip.cpp` 只依赖 `xz_dec_init` / `xz_dec_run` / `xz_dec_end` 和 `struct xz_buf`
这几个稳定接口。

升级时把上面 9 个文件整份覆盖即可，`xz_config.h` 保留。
