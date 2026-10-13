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
        versionCode = 8
        versionName = "0.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            // Existing installs use this developer machine's debug certificate.
            // Keep the keystore; migrating to another signing key is a separate change.
            signingConfig = signingConfigs.getByName("debug")
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

fun registerDistributionTask(taskName: String, beta: Boolean) {
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = "Stage a signed, non-debuggable release APK, SHA256SUMS and update.json."
        dependsOn("assembleRelease")
        val tag = providers.gradleProperty("releaseTag").getOrElse(
            "v${android.defaultConfig.versionName}" + if (beta) "-beta.1" else "")
        val notes = rootProject.file(providers.gradleProperty("releaseNotesFile")
            .getOrElse("docs/releases/${android.defaultConfig.versionName}.md"))
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
        val output = layout.buildDirectory.dir("outputs/" + if (beta) "beta-release" else "release")
        inputs.file(apk)
        inputs.file(notes)
        inputs.file(rootProject.file("tools/stage_release.py"))
        inputs.property("releaseTag", tag)
        outputs.dir(output)
        val sdkTools = android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}")
        commandLine("python3", rootProject.file("tools/stage_release.py"),
            "--apk", apk.get().asFile, "--aapt", sdkTools.resolve("aapt"),
            "--apksigner", sdkTools.resolve("apksigner"), "--output", output.get().asFile,
            "--tag", tag, "--notes", notes)
    }
}
registerDistributionTask("stageBetaApk", beta = true)
registerDistributionTask("stageReleaseApk", beta = false)

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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
