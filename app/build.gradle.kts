plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.medicinecabinet"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.medicinecabinet"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.1.11"
        // 服务地址是公开接口；部署验证完成后固化地址，管理员凭据不能进入 APK。
        val catalogAddress = providers.gradleProperty("catalogServerUrl")
            .orElse("https://medicine-api.eecld.icu").get()
        // 支持构建时固定的自有 HTTPS 域名；运行时仍没有任意服务器地址入口。
        val catalogOriginPattern = Regex(
            "https://(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"
        )
        require(catalogAddress.isEmpty() || (catalogAddress.length <= 261 && catalogOriginPattern.matches(catalogAddress))) {
            "catalogServerUrl 必须是固定的 HTTPS 域名地址，不能包含认证、端口、路径或参数。"
        }
        buildConfigField("boolean", "CATALOG_SERVER_ENABLED", "true")
        buildConfigField("String", "CATALOG_SERVER_URL", "\"$catalogAddress\"")
        buildConfigField("boolean", "CATALOG_SERVER_AVAILABLE", catalogAddress.isNotEmpty().toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".local/debug.keystore")
    }
    // 长期发行密码由项目脚本解密后仅传入构建进程，不写入源码或 Gradle 属性。
    val releasePassword = providers.environmentVariable("MEDICINE_RELEASE_STORE_PASSWORD").orNull
    if (!releasePassword.isNullOrBlank()) signingConfigs.create("distribution") {
        storeFile = rootProject.file(".local/release-signing.keystore")
        storeType = "PKCS12"
        storePassword = releasePassword
        keyAlias = "medicinecabinet"
        keyPassword = releasePassword
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (!releasePassword.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            // 本地体验版采用优化构建，复用已授权的开发签名，以便覆盖安装。
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
    // 同一构建输出 arm64 小包及通用包，不通过修改 ZIP 破坏签名。
    splits {
        abi {
            isEnable = providers.gradleProperty("splitApks").orNull == "true"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val testHome = rootProject.file(".local/test-home")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // 模拟器下载锁与运行时缓存始终落在项目内，避免写入盘根或全局用户目录。
            it.doFirst {
                check(testHome.isDirectory || testHome.mkdirs()) {
                    "无法创建项目测试缓存目录：${testHome.absolutePath}"
                }
            }
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("maven.repo.local", rootProject.file(".local/test-home/.m2/repository").absolutePath)
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.work:work-testing:2.9.1")
}
