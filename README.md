# NekoSU Manager

Mochi 风格界面的 KernelSU Manager 复刻工程（UI 层，参考 [KernelSU PR #4005](https://github.com/tiann/KernelSU/pull/4005)）。

## 环境

- JDK 17（Gradle 运行与编译目标）
- Android SDK：compileSdk 37、build-tools 37、platform-tools
- Gradle 9.1.0（已带 wrapper）

release 默认启用 R8（`isMinifyEnabled` + `isShrinkResources`），混淆映射在
`app/build/outputs/mapping/release/mapping.txt`，排查线上崩溃时用它还原行号。

在 Android Studio 里直接 `Open` 本目录即可，首次同步会自动生成 `local.properties`。命令行构建：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

如果 `services.gradle.org` 下载超时，可先把 wrapper 的 `distributionUrl` 指到镜像
（如 `https://mirrors.cloud.tencent.com/gradle/gradle-9.1.0-bin.zip`），或本机装好 Gradle 9.1.0 直接 `gradle assembleDebug`。

## 结构

```
app/src/main/kotlin/com/nekosu/manager/
├── MainActivity.kt          入口，套 NekoSUTheme + MochiRoot
├── data/InstalledApps.kt    真实应用列表（PackageManager）+ 图标缓存
├── data/AppGroups.kt        按 uid 归并应用（共享 uid 组、代表应用、属主名）
├── data/SuProfile.kt        按 uid 的超级用户配置（本地 JSON 存储，含旧包名数据迁移）
├── data/DeviceInfo.kt       真实设备信息采集 + 启动预热缓存（首帧即完整）
├── data/MockData.kt         仍为假数据的部分（5 个模块、KernelSU 运行状态）
├── ui/MochiRoot.kt          底部四 Tab 容器
├── ui/home/                 首页
├── ui/superuser/            超级用户（真实应用列表）
├── ui/profile/              应用配置页（是否放行 / uid / gid / 附加组 / 能力位 / SELinux）
├── ui/module/               模块
├── ui/settings/             设置
├── ui/mochi/Bits.kt         图标色块 / 开关行 / 应用图标占位
└── ui/theme/                Mochi 配色、字体、主题入口
```

## 说明

- 包名 `com.nekosu.manager`，应用名 `NekoSU`，minSdk 24 / targetSdk 37。
- 超级用户页的应用列表来自真实 PackageManager（见 data/InstalledApps.kt）；
  授权是本地 SharedPreferences 记录，没有写进 KernelSU 白名单。
- 首页设备信息为真实采集（见下文）；模块列表仍是静态模拟，设置页开关只改本地状态。
- 共享 uid 的应用合并为一行，配置按 uid 存取，组内所有包共用（见下文）；
- 应用配置页已移植（点击超级用户列表任意一行进入）；模块仓库、安装/刷写、外观、Sulog、关于、WebUI 尚未移植。
- 二级页面用 `MochiNavHost`：iOS 侧推转场 + 跟手侧滑返回（预测性返回），路由在 `ui/superuser/SuperUserFlow.kt`。
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
