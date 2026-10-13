import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.edu.bucea.library"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.bucea.library"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
}

// Keep beta updates compatible with the debug-signed APKs already distributed.
// The staged file name is also the actual GitHub download name, including version.
tasks.register("stageBetaApk") {
    group = "distribution"
    description = "Stage the debug-signed beta APK and its SHA-256 checksum for GitHub Releases."
    dependsOn("assembleDebug")

    val releaseTag = "v${android.defaultConfig.versionName}-beta.1"
    val sourceApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val outputDirectory = layout.buildDirectory.dir("outputs/beta-release")
    val artifactName = "北建大图书馆选座-$releaseTag.apk"
    val stagedApk = outputDirectory.map { it.file(artifactName) }
    val checksumFile = outputDirectory.map { it.file("SHA256SUMS") }

    inputs.file(sourceApk)
    inputs.property("releaseTag", releaseTag)
    outputs.file(stagedApk)
    outputs.file(checksumFile)

    doLast {
        val apk = stagedApk.get().asFile
        apk.parentFile.mkdirs()
        sourceApk.get().asFile.copyTo(apk, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        checksumFile.get().asFile.writeText("$sha256  $artifactName\n", Charsets.UTF_8)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.20.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
