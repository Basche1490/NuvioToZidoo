import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI stamps these from the GitHub run number, so every build installs over the last.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.0.0-local"

android {
    namespace = "io.github.basche1490.nuviotozidoo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.basche1490.nuviotozidoo"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // A fixed, public key (like Android Studio's debug key) so each new APK installs over
        // the previous one. It protects nothing; see README.
        create("fork") {
            storeFile = rootProject.file("signing/nuviotozidoo.keystore")
            storePassword = "android"
            keyAlias = "nuviotozidoo"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fork")
        }
        debug {
            signingConfig = signingConfigs.getByName("fork")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
