plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.lucateam_wt.tumbler"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.lucateam_wt.tumbler"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Raises VisibleForTests to an error; see the comment in app/lint.xml.
        lintConfig = file("lint.xml")
    }
    // Lets instrumented tests read the exported schema from the test APK's assets.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// Room schemas are exported here and committed, so every schema change shows up in review.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Only for scrypt (org.bouncycastle.crypto.generators.SCrypt) in the backup format; the
    // Bouncy Castle JCA provider is never registered. R8 strips everything else.
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    // room-testing (androidTest) brings kotlinx-serialization-json 1.8.1, which needs
    // serialization-core 1.8.1 at runtime (1.7.3 throws AbstractMethodError). AGP pins the
    // androidTest classpath to the debug app's versions, so raise it for the debug variant
    // only; the release classpath is untouched. Remove once the AndroidX libraries resolve 1.8.x.
    constraints {
        debugImplementation(libs.kotlinx.serialization.bom) { version { strictly(libs.versions.kotlinxSerialization.get()) } }
        debugImplementation(libs.kotlinx.serialization.core) { version { strictly(libs.versions.kotlinxSerialization.get()) } }
        debugImplementation(libs.kotlinx.serialization.core.jvm) { version { strictly(libs.versions.kotlinxSerialization.get()) } }
    }
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}