plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.paul.voicekeys"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.paul.voicekeys"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        ndk {
            abiFilters += "arm64-v8a"
            if (providers.gradleProperty("includeEmulator").orNull == "true") abiFilters += "x86_64"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("onnx", "model") }
    packaging { jniLibs.useLegacyPackaging = false }
    sourceSets["test"].resources.srcDir("src/main/assets")
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.mockito:mockito-core:5.17.0")
}

val checkOfflineAssets by tasks.registering {
    doLast {
        val required = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
        check(required.all { file("src/main/assets/kws/$it").isFile } &&
            file("libs/sherpa-onnx-2.0.1.aar").isFile) {
            "Offline recognition files are missing. Run: python scripts/prepare_assets.py (from the repository root)."
        }
    }
}
tasks.named("preBuild") { dependsOn(checkOfflineAssets) }
