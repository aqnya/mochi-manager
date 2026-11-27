# NekoSU Manager

Mochi 风格界面的 KernelSU Manager 复刻工程（UI 层，参考 [KernelSU PR #4005](https://github.com/tiann/KernelSU/pull/4005)）。

## 环境

- JDK 17（Gradle 运行与编译目标）
- Android SDK：compileSdk 37、build-tools 37、platform-tools
- AGP 9.1.1 + Gradle 9.3.1（已带 wrapper；AGP 9.0.x 最高只支持 API 36，从 9.1 起才正式支持 37）

release 默认启用 R8（`isMinifyEnabled` + `isShrinkResources`），混淆映射在
`app/build/outputs/mapping/release/mapping.txt`，排查线上崩溃时用它还原行号。

**APK 由 GitHub Actions 构建，本地不再编译出包。** `.github/workflows/build.yml` 在
main 的每次 push 与手动 `workflow_dispatch` 上跑单测 + 签名 release，产物是 artifact
`NekoSU-release-signed`（含 APK 与 SHA-256 清单）；PR 只跑单测 + debug 包。

### release 签名配置

`app/build.gradle.kts` 的 `signingConfigs.release` 从环境变量（CI）或
`local.properties`（本地应急）读四项材料，缺任一项 `assembleRelease` 会报未签名而不是
产出装不上的废包。CI 在仓库 **Settings → Secrets and variables → Actions → New repository secret**
配四个 secret（值与本地 `mochi` keystore 一致）：

| Secret | 值 |
| --- | --- |
| `NEKOSU_KEYSTORE_BASE64` | `base64 -w0 /path/to/mochi-release.keystore` 的输出 |
| `NEKOSU_KEYSTORE_PASSWORD` | `mochi2026` |
| `NEKOSU_KEY_ALIAS` | `mochi` |
| `NEKOSU_KEY_PASSWORD` | `mochi2026` |

证书 SHA-256 指纹固定为 `ce69488700849f24df4ec9cbf7c88652d45749bc68df3981003308d221d5969f`，
流水线里 `apksigner verify --print-certs` 会打印它供核对；换证书会让内核验签失败。

本地要应急构建签名包时，在 `local.properties`（已 gitignore，不要提交）写：

```properties
nekosu.keystoreFile=/path/to/mochi-release.keystore
nekosu.keystorePassword=mochi2026
nekosu.keyAlias=mochi
nekosu.keyPassword=mochi2026
```

然后 `./gradlew assembleRelease` 直接产出已签名 APK（无需再手动 zipalign / apksigner）。
测试 JVM 需要 JDK 21：本地自动找 `/opt/jdk-21.0.13.1+2`，CI 由 `NEKOSU_TEST_JAVA_HOME`
指向 setup-java 装的 21。普通命令行构建（debug）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

如果 `services.gradle.org` 下载超时，可先把 wrapper 的 `distributionUrl` 指到镜像
（如 `https://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip`），或本机装好 Gradle 9.3.1 直接 `gradle assembleDebug`。

## 结构

```
app/src/main/kotlin/com/nekosu/manager/
├── MainActivity.kt          入口，套 NekoSUTheme + MochiRoot
├── data/InstalledApps.kt    真实应用列表（PackageManager）+ 图标缓存
├── data/AppGroups.kt        按 uid 归并应用（共享 uid 组、代表应用、属主名）
├── data/SuProfile.kt        按 uid 的超级用户配置（内核 profile 为准，本地 JSON 兜底）
├── data/DeviceInfo.kt       真实设备信息采集 + 启动预热缓存（首帧即完整）
├── data/AppearanceSettings.kt 外观设置（玻璃开关 + 玻璃样式，settings 偏好持久化）
├── data/ModuleStore.kt      已安装模块（ncore `module list --json`）+ 启停/卸载
├── data/MockData.kt         仅剩运行状态占位数据
├── data/registry/           模块仓库（modules.kernelsu.org v1）：解析、缓存、校验下载
├── data/RootShell.kt        `su -c` 执行封装（优先 /system/bin/su，带超时，不引 libsu）
├── neko/NekoNative.kt       JNI 绑定（与 app/src/main/cpp/neko_jni.cpp 对应）
├── neko/NekoKernel.kt       内核查询：版本、授权表、特性位
├── neko/Ncore.kt            ncore 部署与 CLI（模块安装/启停/卸载/action）
├── data/Bugreport.kt        日志收集：脚本、设备信息、tar.gz 打包
├── ui/MochiRoot.kt          根导航：标签骨架（底部四 Tab）+ 二级页都挂在这条栈上
├── ui/home/                 首页
├── ui/superuser/            超级用户（真实应用列表）
├── ui/profile/              应用配置页（是否放行 / uid / gid / 附加组 / 能力位 / SELinux）
├── ui/module/               模块（已安装列表 → 模块仓库 → 仓库详情 / 安装输出页）
├── ui/settings/             设置 → 主题设置 ThemeScreen
├── ui/mochi/Bits.kt         图标色块 / 开关行 / 应用图标占位
└── ui/theme/                Mochi 配色、字体、主题入口
```

## 底层接线（neko）

底层不再自己拼 `su` / `ksud` 命令，而是接上 [neko](https://github.com/nksu-crew/NekoSU/tree/main/neko)：
它是 NekoSU 的用户态运行时，内核控制通道、模块运行时、su / resetprop / init 事件都在里面。

```
neko/                       上游 neko 包（已移除 jni/）
├── ncore/                  模块安装/启停/卸载、su、resetprop、init 事件、manager 扫描
├── kzcall/ proto/ store/   内核调用封装、protobuf 模型、profile 存储
├── third_party/ksu_props/  属性读写
└── build-ncore.sh          交叉编译 ncore → app/src/main/jniLibs
uapi/                       内核 UAPI 头（ioctl/selinux/version），kernel 与 ncore 共用一份
app/src/main/cpp/           本工程自己的 JNI 桥（C++）
```

### JNI 由本工程实现

上游 `neko/jni` 用 Rust 把整套内核接口导出成 `Java_me_nekosu_aqnya_core_Ncore_*`，这里不保留它：

- `app/src/main/cpp/neko_jni.cpp` 按 `uapi/ioctl.h` 直接走 `prctl(NKSU_PRCTL_GET_CTL_FD)` + `ioctl(IOC_CMD)`，
  控制 fd 从 `/proc/self/fd` 里按 `[fmac_ctl]` 找（prctl 只负责挂 fd，不改写返回值）；
- 只导出真正用到的几个：`version / available / hasUid / addUid / delUid / listProfiles / setProfile /
  featureList / featureGet`，其余（boot 镜像 patch、selinux 批量注入等）不接；
- Kotlin 侧是 `com.nekosu.manager.neko.NekoNative`，`libneko.so` 加载失败或拿不到 fd 时全部返回 null。

### ncore 的编译与部署

```sh
./neko/build-ncore.sh                                  # 默认：未签名，debug 内核可直接用
SIGNING_KEY=ncore.pem SIGNING_PASSWORD=... ./neko/build-ncore.sh   # 只有 release 内核需要
```

脚本用 NDK clang 交叉编译（`aarch64-linux-android`，API 31），产物直接放到
`app/src/main/jniLibs/arm64-v8a/libncore.so`。它其实是**可执行文件顶着 .so 的名字**：
内核要按 `/data/app/<pkg>/lib/arm64-v8a/libncore.so` 这个路径引导它，所以 APK 里必须解压成真实文件
（`packaging.jniLibs.useLegacyPackaging = true`）。

引导和鉴权都不归管理器管：内核的 exec 探针只认 `/data/adb/ncore` 这个路径，init 在
post-fs-data 执行它时自动挂上控制 fd；ncore 自己在 boot 阶段扫 `packages.xml`（用编译期
指纹 `NKSU_MANAGER_CERTHASH`，默认 `ce6948…969f`）把管理器 uid 交给内核。管理器只调它的
CLI，不复制镜像、不跑 `ncore install`、也不做 `manager scan`——重复做只会和内核抢同一份状态。
指纹是 ncore 的编译期常量，换 keystore 仍然要重编 ncore。

> 自举：`su` 由内核 hook 重定向到 `/data/adb/ncore`。debug 内核跳过镜像验签，会直接把 APK
> 里的 `libncore.so` 铺到 `/data/adb/ncore`；release 内核只接受带官方私钥签名的镜像。

### 安装状态

首页状态卡不再有任何编译期常量：`data/NekoStatus.kt` 的 `NekoStatusRepository` 是唯一来源。

| 字段 | 来源 |
| --- | --- |
| 内核版本 | `IOC_GET_VERSION`（`NekoKernel.version()`），读不到即 null |
| ncore | `su -c test -x /data/adb/ncore`，su 起不来记 Unknown |
| 模块数 | `ncore module list --json` 的条数，内核通道不通时为 null |

据此分四态：`Checking`（还没探完）/ `Active`（通道可用）/ `Detached`（ncore 在但没连上内核）
/ `Absent`（都没有，探测失败也算这一态）。未安装时状态卡显示「未安装」，模块计数显示「—」，
不会再出现写死的「运行中 v1.1.0-12851 / GKI」。

**首帧不容忍中间态**：`MainActivity.onCreate` 在 `setContent` 之前同步调用
`NekoStatusRepository.preload()`，探完才让首页组合，所以第一帧就是 `Active / Detached / Absent`
的真实状态，不会闪一下「正在检查」。首页自身还有一道 `remember { preload }` 幂等守卫，
覆盖深层链接 / 测试等未走 MainActivity 的入口。启动探测设了 3s 硬上限（防止 su 授权弹窗
卡死主线程），超时按 `checked=true` 的「未安装」落盘，不会停在 Checking；
进入首页后 `refresh()` 再异步重探一次，覆盖刚装完模块的场景——它只在探测结束时一次性
写入完整状态，不会把界面退回中间态。

### 签名约束

内核的 ncore 验签一共三处，底层都落到 `nksu_ncore_verify_file`：

| 检查 | 调用点 | debug 构建（`CONFIG_NKSU_DEBUG=y`） |
| --- | --- | --- |
| 加载 / 执行 ncore 镜像 | `spawn.c` → `nksu_ncore_verify_path` | 直接 `return 0` |
| 管理器上报的 ncore 路径 | `manager.c` → `nksu_ncore_verify_file` | 直接 `return 0` |
| ioctl 调用者是不是 ncore | `manager.c` → `nksu_ncore_verify_caller` | 直接 `return 0` |

上游已把三处统一到 `CONFIG_NKSU_DEBUG`：**debug 内核不做任何 ncore 签名校验**——自签名、
甚至完全没签名的镜像都能被内核引导、被 spawn，ncore 在 boot 阶段也能把管理器 uid 交给内核。

| 场景 | 结果 |
| --- | --- |
| debug 内核 + 本仓库默认构建（未签名 ncore） | 三处全放行；内核引导 ncore → ncore 自报 manager uid → 应用 `prctl` 拿到控制 fd，授权直连内核 |
| release 内核 + 本仓库默认构建 | 镜像验签失败，ncore 起不来，内核通道不可用；应用退回本地授权表和 `ksud` |
| release 内核 + 上游签名私钥 | 与官方管理器一致，全部走内核通道 |

管理器的角色只是消费结果：不复制 ncore、不跑 `ncore install`、也不做 `manager scan`
（`nksu_ncore_verify_caller` 那条路留给 ncore 自己）。`packages.xml` 里的证书比对发生在
ncore 的 boot 阶段，用编译期指纹，和上面的镜像验签是两回事。

`NekoKernel.version()` 为 null 时，`SuProfileStore` 全部走本地存储，`ModuleInstaller` 退回
`ksud`——不会因为缺内核模块就假装成功。


## 说明

- 包名 `com.nekosu.manager`，应用名 `NekoSU`，minSdk 24 / targetSdk 37。
- 超级用户页的应用列表来自真实 PackageManager（见 data/InstalledApps.kt）；
  授权是本地 SharedPreferences 记录，没有写进 KernelSU 白名单。
- 首页设备信息为真实采集（见下文）；模块列表来自 `/data/adb/modules`，启停/卸载写回磁盘，设置页开关只改本地状态。
- 共享 uid 的应用合并为一行，配置按 uid 存取，组内所有包共用（见下文）；
- 应用配置页已移植（点击超级用户列表任意一行进入）。
- 模块页右上角的商店按钮进入**真实模块仓库**（`modules.kernelsu.org`），可搜索、按更新时间 / 名称 / 星标排序、
  看详情并从 GitHub Release 下载安装；加号是**本地 ZIP 安装**入口（`ACTION_GET_CONTENT`，支持多选，参考
  KernelSU 的交互：单个直接装、多个先列文件确认）。已安装列表来自 ncore，WebUI / Sulog / 关于页尚未移植。
- 设置页的「保存日志 / 发送日志」已接真实实现（见下文），不再是无动作的空行。
- 首页右上角的电源按钮接入了**重启菜单**，用 mochi 的 `MochiBarIconDropdown`（顶栏图标按钮同款材质、融球 /
  果冻 / 按住滑选，保留 navId 跨页共享转场），菜单项参照上游 KernelSU 的 `RebootListPopup`：
  普通重启 / 用户空间重启 / Recovery / Bootloader / EDL。「用户空间重启」按
  `PowerManager.isRebootingUserspaceSupported` 过滤，不支持的设备不显示；
  执行走 root shell 的 `svc power reboot $reason || /system/bin/reboot $reason` 双保险，
  Recovery 前先按一次 KEYCODE_POWER 消掉厂商 recovery 的「清除数据」提示
  （`data/Reboot.kt`）。上游的 `soft_reboot` 依赖 `ksud soft-reboot`，ncore 无对等
  守护进程命令，故不提供。
- 二级页面用根级 `MochiNavHost`（`ui/MochiRoot.kt`）：标签骨架是它的根页，配置页 / 模块仓库 / 仓库详情 /
  安装页 / 主题页都推到同一个栈上。所以推入二级页时整页连同底部标签栏一起往左退、被新页盖住，
  二级页里不会再出现标签栏；返回手势同时把这一页推回去（iOS 的 `hidesBottomBarWhenPushed`）。
  标签页自己的滚动位置照旧保留（栈顶下面那页保持组合）。
- 设置 → 主题是真实页面：玻璃开关切换 `MochiTheme` 的 materials（tab bar、顶栏按钮、菜单、弹窗
  一起换），值存在 `settings` 偏好里，重启保留；开启后同组多出「玻璃样式」，按 mochi demo 的
  「材质 → 玻璃样式」分档，可选 **标准**（`MochiGlassDefaults.Regular`，更透、折射更明显）和
  **厚实**（`MochiGlassDefaults.Thick`，磨砂更实、深色壁纸下文字更清楚），选择立刻生效并落盘。
  demo 里的「毛玻璃」(Frosted) 尚未进入 mochi-compose 0.3.0 的公开 API，因此暂不提供；
  其余设置条目仍是移植中的占位。
- 超级用户页走**下拉刷新**：`MochiLazyLargeTitleScaffold(onRefresh = ...)`，`onRefresh` 内直接完成加载
  （`ui/superuser/RefreshGate.kt`），保证最短展示 350ms、最长 15s 必返回，读列表失败只保留旧数据、不影响收起。
- 依赖 `io.github.feice11:mochi-compose:0.3.0`，其 `com.kyant:capsule` 为 Java 21 字节码，
  本地单元测试若用 JDK 17 会报 `UnsupportedClassVersionError`，需用 JDK 21 跑测试 JVM
  （`app/build.gradle.kts` 里的 test 配置已处理，路径按需修改）。

## 小米「获取应用列表」权限适配

按小米开放平台《获取应用列表权限的适配说明》（pId=1619）实现。HyperOS / MIUI 13+ 在
`QUERY_ALL_PACKAGES` 之上额外管控 `getInstalledPackages` 系列接口，无权限时只返回调用方自身。

| 环节 | 实现 |
| --- | --- |
| 清单声明 | `<uses-permission android:name="com.android.permission.GET_INSTALLED_APPS" />` |
| 是否支持动态申请 | `getPermissionInfo("com.android.permission.GET_INSTALLED_APPS")`，且 `packageName == "com.lbe.security.miui"` |
| 申请时机 | 首次进入超级用户页弹一次系统授权框（`RequestPermission` 契约），结果回调后重新加载 |
| 拒绝之后 | 不再重复弹窗（SharedPreferences 记 `asked_runtime_permission`），改为列表顶部的引导卡 |
| 引导卡 | 「去开启」跳小米权限编辑页（`miui.intent.action.APP_PERM_EDITOR` + `extra_pkgname`）→ 失败退到应用详情页 → 设置首页；「重新检查」手动重试 |
| 判定受限 | 列表里一个第三方应用都没有（自身包名已排除）即认为被限制 |
| 返回刷新 | `ON_RESUME` 时重新加载，用户从系统设置改完回来立刻生效 |

非小米设备或系统不支持动态申请时，只保留引导卡（文案切换成通用版本），不影响正常读取。

## 共享 UID 应用（sharedUserId）

KernelSU 的 su 白名单与 profile 都以 **uid** 为粒度，所以列表不能按包名平铺：

| 环节 | 实现 |
| --- | --- |
| 归并 | `groupApps()` 按 `ApplicationInfo.uid` 分组，一组一行（`data/AppGroups.kt`） |
| 代表应用 | `pickPrimary()`：优先 `sharedUserLabel`，其次 `android.uid.system/phone/bluetooth/nfc` 的固定映射，最后包名最短者 |
| 标题 | `sharedUserLabel` 文案 > `android.uid.*` 的 sharedUserId > 主应用名 |
| 副标题 | 共享时显示「包含 N 个应用 · UID x」；搜索命中时列出命中的包名 |
| 配置存储 | `SuProfileStore` 用 uid 做主键，旧版按包名存的 JSON 在启动时用 `getPackageUid` 迁移 |
| 配置页 | 共享 uid 时多一段「共享 UID 应用」列组员，并说明授权对整组生效 |

这样 `android.uid.system` 这类组不会出现多行互相矛盾的开关，授权一次即整组生效。

## 设备信息采集

首页「设备信息」不再写死 Pixel，全部取自本机，不需要 root：

取值方式对齐 KernelSU PR #4005 依赖的 `DeviceNameHelper` / `SELinuxChecker` / `HomeViewModel`：

| 字段 | 来源 |
| --- | --- |
| 设备型号 | 按 `Build.MANUFACTURER` 分派：小米/红米/POCO 读 `ro.product.marketname`（ODM/vendor/system 前缀依次回退）、OPPO 系 `ro.vendor.oplus.market.name`、vivo/iQOO `ro.vivo.market.name`、华为/荣耀 `ro.config.marketing_name`、中兴/努比亚 `ro.vendor.product.ztename`、三星 `SemFloatingFeature`（反射 + `floating_feature.xml`）；都取不到才回退 `MANUFACTURER BRAND MODEL`（重复词只留一个） |
| 系统版本 | `ro.mi.os.version.name` → `HyperOS 2.0.5.0`；`ro.miui.ui.version.name` → `MIUI V816`；再拼 `Android ${Build.VERSION.RELEASE}` |
| 内核版本 | `Os.uname().release` → `/proc/version` → `os.version` |
| SELinux | 执行 `getenforce`；被拒（stderr 以 `Permission denied` 结尾）判定为 Enforcing；拿不到再读 `/sys/fs/selinux/enforce`，selinuxfs 缺失则 Disabled |
| Seccomp | `Os.prctl(PR_GET_SECCOMP = 21)` 的返回值：-1 不支持、0 关闭、1 严格、2 过滤 |
| 管理器版本 | `PackageManager.getPackageInfo` 的 versionName + versionCode |
| 系统指纹 | `Build.FINGERPRINT` |

属性读取先反射 `android.os.SystemProperties.get`，被隐藏 API 策略拦截时退回 `getprop` 子进程。

所有读取都单独 runCatching，某一项被 ROM 限制只影响那一格（显示「未知」/「不支持」），不会影响页面；
采集在 `MainActivity.onCreate` 的首帧之前同步完成并缓存，首页直接读缓存，**不存在占位符或「—」的中间态**。

## 模块仓库

对齐 KernelSU 管理器（上游 `RegistryClient` / `RegistryParser` / `ModuleRepoRepository`）的 v1 协议，
数据来自 `https://modules.kernelsu.org/api/v1`，不依赖第三方聚合站：

| 环节 | 实现 |
| --- | --- |
| 协议 | 先取 `index.json` 拿 `modules` 路径，再取模块列表；两份文档的 `buildId` 必须一致，`schemaVersion` 不是 1 直接拒绝 |
| 缓存 | `filesDir/module_registry` 存完整副本；刷新失败时退回上一次的副本并标记 `stale`，界面提示「可能不是最新」而不是清空 |
| 搜索 | 匹配 id / 名称 / 摘要 / 作者 / 标签，忽略大小写 |
| 排序 | 更新时间（stable 的 `publishedAt` 倒序）、名称（中文 `Collator`）、星标（未知星标的排已知之后，同分按时间） |
| 图标 | 相对路径拼站点域名，按 URL 落盘 + 12 MB 位图 LRU，列表滚动不重复解码 |
| 下载 | 只接受 `https://github.com/<owner>/<repo>/releases/download/...`，非 GitHub 主机、带 query、带 userinfo 一律丢弃 |
| 校验 | 下载后核对 Release 记录的字节数与 SHA-256，不一致删除临时文件并报错，绝不交给 `ksud` |
| 安装 | 优先 `ncore module install <path>`；没有 ncore 时退回 `su -c ksud module install <path>`（依次试 `/data/adb/ksud` 与 PATH）。拿不到 root、返回码非 0 都给可读原因，不会假装成功 |
| 脚本条目 | `kind: script` 只展示、跳网页运行，不提供安装按钮 |
| 刷新 | 与超级用户页同一套 `runRefresh`：最短 350ms、上限 30s（要联网，比读本地列表放宽），取消能向上传播 |

下载的 ZIP 放在 `cacheDir/module_downloads`，按 SHA-256 命名，重复安装同一版本直接复用。

本地 ZIP 安装走同一个 `ModuleInstaller`：SAF 返回的 `content://` 先由 `LocalModuleImport.stageAll()`
复制到 `cacheDir/module_import` 并校验 ZIP 魔数（512 MB 上限），再把真实路径交给 ncore/ksud——
root shell 读不了文档提供者的 fd，必须落盘。

开始安装后进入**安装输出页**（`ui/module/ModuleInstallScreen.kt`），版式照 KernelSU PR 的刷写页
（`FlashScreen`）来做，不额外加状态卡和对话框：

| 能力 | 实现 |
| --- | --- |
| 顶栏 | 标题跟着状态走：正在安装 / 安装成功 / 安装失败；右侧下载图标保存日志 |
| 实时输出 | `RootShell.execStreaming()` 按行回调，ncore 的 `ui_print` 一到就追加到整块等宽终端区（12sp、可选中复制、自动滚到底） |
| 交互脚本 | 提示就是脚本自己打的一行 `ui_print`（如「按音量上键继续」），用户按**物理音量键**继续；页面不做输入框，`ESC[H[ESC[J` 清屏序列按清屏处理 |
| 会话保持 | 任务在 `ModuleInstallSession`（应用级 `SupervisorJob`）里跑，旋转屏幕、切后台都不会中断；页面只是订阅者 |
| 返回 | 安装中禁用返回与侧滑，避免脚本跑到一半被切走——与 PR 的 `backEnabled = flashingStatus != FLASHING` 一致 |
| 结果 | 成功后右下角只给一个圆形按钮：模块目录未就位 / 带 `update` 标记（需要重启）显示「重启」，热安装显示「关闭」；失败无悬浮按钮，按返回退出并重读 `/data/adb/modules` |
| 保存日志 | 右上角图标写 `Download/NekoSU/NekoSU_module_install_<时间>.log` |

安装期间保持屏幕常亮（`keepScreenOn`）：交互式脚本等的是按键，屏幕灭了就按不到。

清单必须声明 `android.permission.INTERNET`（没有它 `HttpURLConnection` 直接抛 `SecurityException`，
界面只会显示「加载模块仓库失败」，很难看出是权限问题）；`ManifestPermissionsTest` 盯着这条。
加载失败时页面给出「重试」按钮，缺网络权限时改为直接写明原因，不给无效的重试入口。
站点用 JSON `null` 表示缺失字段，解析时统一按空串处理，避免把 `icon: null` 拼成 `.../null` 的图标地址。

## 日志导出

对齐 KernelSU 的 bugreport，设置 → 保存日志 / 发送日志：

| 环节 | 实现 |
| --- | --- |
| 收集 | 生成一个 sh 脚本一次执行，逐条 `2>/dev/null`，某路径不存在只丢那一份；有 root 走 `su -c`，没有就以应用自身身份跑同一份脚本 |
| 内容 | 设备信息（`basic.txt`）、`getprop`、`getenforce`、进程表、`dmesg`、`logcat -b all`、`mountinfo`、`/proc/modules`、`kallsyms`（临时放开 `kptr_restrict` 再还原）、tombstones / dropbox / pstore / 厂商 diag / `/data/adb/ksu/log`、`/data/adb` 清单、KernelSU 白名单与 `ksud --version` |
| 打包 | `tar czf` 后 `chmod 0644`，文件名 `NekoSU_bugreport_yyyy-MM-dd_HH_mm.tar.gz`，放在 `cacheDir` |
| 保存 | 直接写入公共下载目录 `Download/NekoSU/`（10+ 走 `MediaStore`，`IS_PENDING` 收尾；9 及以下写公共下载目录，无权限时退到应用外部目录），完成后删掉缓存里的临时包，弹窗里给出完整路径 |
| 分享 | `FileProvider`（`${applicationId}.fileprovider`，只暴露 `cache-path`）转成 `content://`，走 `ACTION_SEND` chooser |
| 无 root | 不失败：设备信息、SELinux、进程表、`getprop`、自身 logcat 照旧收集；dmesg、`/data/adb`、tombstones 等读不到的 0 字节输出会被删掉并写进 `missing.txt`，导出前弹窗说明缺了几项，用户可继续或取消 |
| 反馈 | 收集期间有不可取消的进度弹窗；真正的失败（脚本起不来、打包失败、超时）才报错，不会静默失败 |

`su` 走 `data/RootShell.kt`，自己按截止时间轮询读取输出并 `destroy()`，避免某些 ROM 上 `dmesg`
挂住把界面一起拖死；单次输出上限 4 MB。`isRootAvailable()` 先跑一次 `su -c id -u`（会被拒绝、超时、没有 su
都算无 root），之后无 root 分支用 `execAsUser()`（`sh -c`，不经 su）执行收集与打包。
`basic.txt` 里记了 `Root: yes/no`，看报告的人一眼能知道这份日志是哪种身份收集的。

### 为什么不用系统文件选择器

早期版本用 `ActivityResultContracts.CreateDocument("application/gzip")` 让用户挑位置，在 HyperOS 上直接失败：
文档界面拿到 `application/gzip` 后立刻返回一个**没有 extras** 的结果，MIUI 的
`ActivityThread.deliverResultsIfNeeded` 读 `Bundle.getString` 时 NPE，应用只收到 `uri = null`，
表现就是「点保存没反应」。日志里对应这一行：

```
E ActivityThread: fail in deliverResultsIfNeeded java.lang.NullPointerException ...
```

改成 `MediaStore.Downloads` 直接写公共下载目录后不再依赖文档界面，也不需要任何运行时权限；
旧系统（API 28 及以下）才需要 `WRITE_EXTERNAL_STORAGE`（清单里带 `maxSdkVersion="28"`）。
