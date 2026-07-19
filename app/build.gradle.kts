plugins {
    id("com.android.application")
}

group = "com.zeropointsix.dobaosay"
version = "0.2.0-SNAPSHOT"

android {
    namespace = "com.zeropointsix.dobaosay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeropointsix.dobaosay"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-ui"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":asr-core"))
    implementation(project(":provider-doubao"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
