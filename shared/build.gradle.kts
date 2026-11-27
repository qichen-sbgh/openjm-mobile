import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // 书架持久化用 JSON 落盘（见 docs/tech/mobile.md §5），@Serializable 需要这个编译器插件
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "com.openjm.reader.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }

        // 协议向量回放跑在 JVM host test 上：不需要真机，也能覆盖 androidMain 的 javax.crypto 实现。
        withHostTestBuilder { }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // PDF 渲染：PDFium 预编译二进制，全链路不碰平台 PdfRenderer（D6，避开 ROM 字体与缩放问题）
            implementation(libs.pdfiumandroid)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
