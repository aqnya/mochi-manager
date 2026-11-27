plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.nekosu.manager"
    compileSdk = 37
    // neko 的 ncore 只交叉编译到 arm64，管理器也没必要为其它 ABI 带一份空壳。
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.nekosu.manager"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // JNI 桥由本工程自己用 C++ 实现（app/src/main/cpp），不引入 neko 的 Rust jni crate。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            // R8：压缩类名 + 裁剪未用代码与资源。material-icons-extended 体积很大，
            // 开启后只保留真正引用的图标，release 包从 14 MB 降到 1.8 MB 左右。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // ncore 是「顶着 .so 名字的可执行文件」：内核要按路径读它、首次启动还要
            // 复制到 /data/adb/ncore。必须解压成真实文件，不能留在 APK 里直接映射。
            useLegacyPackaging = true
            // Cargo 已经 strip 过，别再让 AGP 调 NDK 的 strip 处理一遍
            keepDebugSymbols += "**/libncore.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// 仅用于本地 Robolectric 渲染验证：mochi-compose 依赖的 com.kyant:capsule 是 Java 21 字节码，
// 测试 JVM 需要 21 才能加载（编译产物仍为 Java 17，Android 侧由 D8 处理）。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val jdk21 = file("/opt/jdk-21.0.13.1+2/bin/java")
    if (jdk21.exists()) executable = jdk21.absolutePath
    maxHeapSize = "2g"
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // mochi compose
    implementation(libs.mochi.compose)
    implementation(libs.mochi.icons)

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
