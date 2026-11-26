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
