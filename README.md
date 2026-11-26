# NekoSU Manager

Mochi 风格界面的 KernelSU Manager 复刻工程（UI 层，参考 [KernelSU PR #4005](https://github.com/tiann/KernelSU/pull/4005)）。

## 环境

- JDK 17（Gradle 运行与编译目标）
- Android SDK：compileSdk 37、build-tools 37、platform-tools
- Gradle 9.1.0（已带 wrapper）

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
├── data/SuProfile.kt        单个应用的超级用户配置（本地 JSON 存储）
├── data/MockData.kt         仍为假数据的部分（设备信息、5 个模块）
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
- 模块、设备信息仍是静态模拟；设置页开关只改本地状态。
- 二级页面（模块仓库、安装、应用配置、外观、Sulog、关于、WebUI）尚未移植。
- 依赖 `io.github.feice11:mochi-compose:0.3.0`，其 `com.kyant:capsule` 为 Java 21 字节码，
  本地单元测试若用 JDK 17 会报 `UnsupportedClassVersionError`，需用 JDK 21 跑测试 JVM
  （`app/build.gradle.kts` 里的 test 配置已处理，路径按需修改）。
