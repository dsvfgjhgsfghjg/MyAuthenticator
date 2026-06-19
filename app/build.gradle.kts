plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "top.leoblog.myauthenticator"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "top.leoblog.myauthenticator"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)

    // WebSocket (OkHttp)
    implementation(libs.okhttp)

    // OkHttp 日志拦截器
    implementation(libs.okhttp.logging.interceptor)

    // Gson (JSON 解析)
    implementation(libs.gson)

    // Retrofit (REST API 调用)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    // Bouncy Castle (国密 SM4 支持)
    implementation(libs.bouncycastle)

    // Coil (图片加载)
    implementation(libs.coil)

    // SwipeRefreshLayout
    implementation(libs.androidx.swiperefreshlayout)

    // EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
