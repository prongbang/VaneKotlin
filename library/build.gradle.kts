plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.inteniquetic.vanekotlin"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // The JVM half of rustls-platform-verifier (the class Rust calls over JNI
    // to reach the Android trust store) is vendored as source under
    // src/main/java/org/rustls/platformverifier, so it needs no dependency
    // here. See that directory for provenance and the local patch.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // @RequiresApi on the UniFFI-generated SystemCleaner wrapper, which is
    // what lets minSdk sit below 34. Compile-only would be wrong: the
    // annotation is CLASS-retention and consumers lint against it.
    implementation("androidx.annotation:annotation:1.9.1")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Benchmark contenders (see src/androidTest/.../benchmark/). androidTest
    // dependencies never reach the published AAR or its consumers — keep the
    // competing clients out of the shipping dependency graph, same rule as
    // vane_benchmark on the Dart side.
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.retrofit2:retrofit:3.0.0")
    androidTestImplementation("org.chromium.net:cronet-embedded:119.6045.31")
}
