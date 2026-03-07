plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lanbing.smsforwarder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lanbing.smsforwarder"
        minSdk = 26
        targetSdk = 34

        // 1) 优先读取 Gradle property，再读取环境变量；没有时回退到默认值
        val versionNameFromProp = (project.findProperty("VERSION_NAME") ?: System.getenv("VERSION_NAME"))?.toString()
        val versionCodeFromProp = (project.findProperty("VERSION_CODE") ?: System.getenv("VERSION_CODE"))?.toString()

        versionName = versionNameFromProp ?: "1.0"
        versionCode = (versionCodeFromProp?.toIntOrNull() ?: 1)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ---------- 新增：从环境变量或 gradle properties 加载签名信息 ----------
    // 优先级：ENV > project property > 本地 my-release-key.jks（便于本地测试）
    signingConfigs {
        create("release") {
            val keystoreFileEnv = System.getenv("KEYSTORE_FILE")
            val keystoreFileProp = project.findProperty("KEYSTORE_FILE")?.toString()
            val keystoreFilePath = keystoreFileEnv ?: keystoreFileProp

            if (keystoreFilePath != null) {
                storeFile = file(keystoreFilePath)
            } else {
                val localKeystore = rootProject.file("my-release-key.jks")
                if (localKeystore.exists()) {
                    storeFile = localKeystore
                }
            }

            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
            keyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS")?.toString()
            keyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD")?.toString()
        }
    }
    // ----------------------------------------------------------------------

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    // 与下面的 Compose 版本保持一致
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

// 调试用：打印当前 versionName/versionCode
tasks.register("printVersion") {
    doLast {
        println("versionName = ${android.defaultConfig.versionName}")
        println("versionCode = ${android.defaultConfig.versionCode}")
    }
}

dependencies {
    // 明确版本，避免 BOM 注入失败引起的问题
    val composeUiVersion = "1.5.4"
    val activityComposeVersion = "1.8.2"
    val material3Version = "1.1.0"
    val lifecycleRuntime = "2.6.2"
    val okhttpVersion = "4.12.0"

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleRuntime")
    implementation("androidx.activity:activity-compose:$activityComposeVersion")

    // Compose UI / graphics / tooling (明确 artifact 名称和版本)
    implementation("androidx.compose.ui:ui:$composeUiVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeUiVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeUiVersion")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeUiVersion")

    implementation("androidx.compose.material3:material3:$material3Version")

    // Icons (material icons extended) — 添加以解决 Icons.Default.History / Message 等引用
    implementation("androidx.compose.material:material-icons-extended:$composeUiVersion")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
}
