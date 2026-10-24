// 根构建脚本：仅声明插件版本（AGP 8.6.0 / Kotlin 1.9.24，与手机端保持一致以便复用本地缓存）
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
