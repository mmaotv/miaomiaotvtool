// 小米盒子工具箱 app 模块
//
// 关键取舍：
//  1) 只打 32 位（armeabi-v7a）——小米盒子 4 系（安卓 6）与 4S Pro 系（安卓 9）
//     的用户空间均为 32 位。本模块不含任何 .so（ADB 客户端为纯 Java 实现），
//     因此包体既能在纯 32 位盒子上安装，也能在 arm64 测试机上安装验证。
//  2) 零第三方依赖（无 Kadb / Okio / BouncyCastle）：
//     目标盒子多为 Android 5.x~9 的低配设备，Kadb 要求 minSdk 28 且依赖现代库，
//     风险高；此处自行实现 ADB 协议（见 adb/AdbChannel.kt），minSdk 21 起即可运行。
//  3) UI 用原生 View + XML：遥控器方向键可天然获得焦点，无需 FocusRequester，
//     且不引入 Compose 运行时。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miaomiaotv.xiaomi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miaomiaotv.xiaomi"
        minSdk = 21
        // 保持 28：目标机多为老式盒子（安卓 6 / 9），较低的目标版本可沿用传统存储策略，
        // 使「安装命令直接读取本地文件」的方式在各版本盒子上都成立。
        targetSdk = 28
        versionCode = 7
        versionName = "1.2.3"

        // 仅保留 32 位 ABI
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 未单独配置签名时使用本机调试密钥，
            // 保证产出的包已签名、可直接安装到设备上。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 显式声明标准库，避免不同 KGP 版本对自动注入行为的差异
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
