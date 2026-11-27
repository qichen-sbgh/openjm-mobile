import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
}

android {
    namespace = "com.openjm.reader.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // 一旦发包就不能再改，否则等于换一个 App、书架数据全丢。
        applicationId = "com.openjm.reader"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // PDFium 的 AAR 带四套 ABI；release 只保留真机用得上的两套（模拟器用 debug 包）。
            // 群内直发（NFR-05）要控制体积：四套全打约 29MB，砍到两套后约 15MB。
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
