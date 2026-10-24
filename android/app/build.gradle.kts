plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.transmusic.converter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.transmusic.converter"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.0"

        // ffmpeg-kit 自带原生库：真机用 arm64/armv7，模拟器用 x86_64。
        // 打包分发时可用 -PabiFilters=arm64-v8a,armeabi-v7a 只留 ARM 架构（体积减半）。
        ndk {
            val override = (project.findProperty("abiFilters") as String?)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            abiFilters += override ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // MD3E 的 Compose API 较多是实验性的，统一放开 OptIn，避免到处写注解
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    buildFeatures {
        compose = true
    }

    // ffmpeg-kit 的原生库需要按传统方式解压（Android 6+ 默认不再解压 .so）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // ---------- MD3E（Material Design 3 Expressive）前端：Jetpack Compose ----------
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 引擎层纯逻辑单测（不需要模拟器，gradle testDebugUnitTest 即可跑）
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---------- 转码引擎 ----------
    // lame(mp3) / opus / vorbis / speex / wavpack / amr / twolame，并内置 aac / flac / alac / ac3 / wma / pcm
    // 若该坐标在你的网络下拉不到（ffmpeg-kit 已归档），见 BUILD-ANDROID.md「依赖不可解析」一节。
    implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")
}
