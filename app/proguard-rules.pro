# NekoSU release 混淆规则
#
# 工程里没有反射式序列化（配置用 org.json 手写编解码），也没有按类名加载自己的类，
# 所以不需要大面积 keep；这里只保留排错所需的信息和几处运行时会读的元数据。

# 崩溃栈保留行号与源文件名，R8 默认会去掉
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod

# 协程 / Compose 运行时会读 Kotlin 元数据
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata <fields>;
}

# DeviceInfo 通过 Class.forName 访问的是 android.os.* 平台类，
# 平台类不参与混淆，无需 keep；这里显式忽略可选注解依赖，避免 R8 报缺失类
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
