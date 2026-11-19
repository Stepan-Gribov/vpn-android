import java.util.Properties

plugins {
    id("com.android.library")
}

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../third_party/amneziawg-android/tunnel/src/main/java")
            // Only Java types used by the retained engine are compiled; AWG is not exposed.
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.collection:collection:1.4.2")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

