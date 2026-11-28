# zstd（单文件解码器）

只用到 **decompress** 部分，用于解压 zip 的 method 95（Zstandard）。
不走系统的 `libzstd.so`：NDK 不导出这个库，设备上也不保证存在，
静态编进来最稳。

- 来源：facebook/zstd `v1.5.6`
- 生成方式（官方脚本，会合并 `lib/decompress` 与 `lib/common`）：

```sh
curl -L https://codeload.github.com/facebook/zstd/tar.gz/refs/tags/v1.5.6 | tar xz
cd zstd-1.5.6/build/single_file_libs
sh create_single_file_decoder.sh        # 产出 zstddeclib.c
```

- 落盘文件：`zstddeclib.c` / `zstd.h` / `zstd_errors.h` / `LICENSE`
- 许可证：BSD-3-Clause OR GPL-2.0，本项目按 BSD-3-Clause 使用（见 `LICENSE`）

升级时重复上面的步骤，把四个文件覆盖过来即可；`unzip.cpp` 只依赖
`ZSTD_decompress` / `ZSTD_isError` / `ZSTD_getErrorName` /
`ZSTD_getFrameContentSize` 这几个稳定 API。
