plugins {
    kotlin("jvm")
    application
    id("org.jmailen.kotlinter")
}

group = "com.zeropointsix.dobaosay"
version = "0.2.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":asr-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.zeropointsix.dobaosay.doubao.DoubaoCliKt")
}

tasks.test {
    useJUnitPlatform()
}
