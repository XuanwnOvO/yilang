import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名等本地信息从 local.properties 读取（该文件不入库，详见 README「发布」一节）
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.yilang"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yilang"
        minSdk = 26
        // targetSdk 必须 < 29：Android 10+ 禁止 targetSdk≥29 的应用执行应用数据目录中的可执行文件
        // （W^X 限制，Termux 同因卡在 28）；本地 sideload 安装 targetSdk 28 无碍
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // 缺省为空：local.properties 里配了 yilang.storeFile 等四项才会真正启用（见 buildTypes.release）
            storeFile = localProps.getProperty("yilang.storeFile")?.let { rootProject.file(it) }
            storePassword = localProps.getProperty("yilang.storePassword")
            keyAlias = localProps.getProperty("yilang.keyAlias")
            keyPassword = localProps.getProperty("yilang.keyPassword")
        }
    }
    buildTypes {
        release {
            // 先不开 R8 混淆（sora-editor 等库需要补规则），非 debuggable 本身已是大头提速
            isMinifyEnabled = false
            if (localProps.getProperty("yilang.storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // targetSdk 28 是刻意为之（见 defaultConfig 注释），关掉 Google Play 的强制检查
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // sora-editor：代码编辑器核心
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.4")
    // 纯 Java XZ 解压：运行时解包 Termux .deb 中的 data.tar.xz（约 110KB，不增加 ABI 体积）
    implementation("org.tukaani:xz:1.9")
}
