plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    ksp("dev.zacsweers.autoservice:auto-service-ksp:1.2.0")
    implementation("com.google.devtools.ksp:symbol-processing-api:2.2.21-2.0.5")
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation("com.squareup:kotlinpoet-ksp:2.2.0")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}