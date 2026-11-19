plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stepangribov.simpleblt.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 38
        versionName = "0.1.4"
        multiDexEnabled = true
        buildConfigField("String", "DISTRIBUTION", "\"Direct\"")

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "TnDtun_${variant.versionName}_${abi}.apk"
            }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libvkturn.so",
                "**/libkindlytransport.so",
                "**/libdnstt.so",
                "**/libslipstream_client.so",
            )
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(project(":awg-tunnel"))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    // Pure-Java Ed25519 verification, including Android API 24.
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation(libs.okhttp)
    implementation(libs.jsch)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}


// Fail before producing an APK that exposes DNS setup but cannot start DNSTT.
val verifyDnsTunnelHelpers by tasks.registering {
    doLast {
        val abis = (project.findProperty("ABI_FILTERS") as? String)
            ?.split(';')?.filter(String::isNotBlank)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val outdated = abis.filter { abi ->
            val helper = file("libs/$abi/libdnstt.so")
            !helper.isFile || !helper.readBytes().toString(Charsets.ISO_8859_1)
                .contains("parallel DNSTT sessions (1-16)")
        }
        check(outdated.isEmpty()) {
            "DNSTT helpers are missing or outdated for ${outdated.joinToString()}. " +
                "Run ../scripts/prepare-native-dependencies.sh from V2rayNG to rebuild parallel-session support."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyDnsTunnelHelpers) }

// Kotlin sends these flags to the bundled helper. Fail before installing an
// incompatible old native binary that would immediately exit on startup.
val verifyTurnResumeHelpers by tasks.registering {
    doLast {
        val helperVersion = requireNotNull(Regex("const val HELPER_VERSION = \"([^\"]+)\"")
            .find(file("src/main/java/com/v2ray/ang/core/VkTurnManager.kt").readText()))
            .groupValues[1]
        val abis = (project.findProperty("ABI_FILTERS") as? String)?.split(';')
            ?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val outdated = abis.filter { abi ->
            val helper = file("libs/$abi/libvkturn.so")
            val contents = if (helper.isFile) helper.readBytes().toString(Charsets.ISO_8859_1) else ""
            listOf(helperVersion, "vless-resume", "control-stdin", "TNDTUN_TURN_CONTROL_READY").any { !contents.contains(it) }
        }
        check(outdated.isEmpty()) {
            "TURN resume helpers are missing or outdated for ${outdated.joinToString()}. Run ../scripts/build-vk-turn.sh."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyTurnResumeHelpers) }
