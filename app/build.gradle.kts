plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.immersive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.immersive"
        minSdk = 21      // Android 5.0
        targetSdk = 36   // Android 16
        versionCode = 7
        versionName = "1.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 不跑 lintVital：沙箱离线环境没有 lint-gradle 依赖，且它不参与打包
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        // debug 包也走 R8 极限压缩 + 资源裁剪，直接用 debug 签名，
        // 保证仍能 adb install -r 覆盖安装旧版（签名不变）。
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Kotlin 反射元数据 / 许可证文件在运行期用不到，直接排除，进一步压体积
    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    // 只依赖 core-ktx：WindowInsetsControllerCompat 等兼容类都在这里
    implementation("androidx.core:core-ktx:1.15.0")
    // 原版同款 ijkplayer（FFmpeg 内核，Java 层 jar + 三个 ABI 的 .so）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
