plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "space.webkombinat.MobileWebCamera"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "space.webkombinat.MobileWebCamera"
        minSdk = 28
        targetSdk = 37
        // リリースのたびに 1 以上増やす（Play は同じ・小さい番号を受け付けない）
        versionCode = 3
        // CI (GitHub Actions) ではタグ名 (v1.2.0 → 1.2.0) を使う
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // CI では Secrets から復元したキーストアを環境変数で渡す。
        // ローカルでは従来どおり Android Studio の「Generate Signed App Bundle」で署名する
        System.getenv("ANDROID_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
tasks.withType<Test>().configureEach {
    System.getProperty("ts.in")?.let { systemProperty("ts.in", it) }
    System.getProperty("ts.out")?.let { systemProperty("ts.out", it) }
}
