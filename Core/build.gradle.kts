plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
    //id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ConfigLib"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.0")
    implementation("org.joml:joml:1.10.8")
    implementation("net.jcip:jcip-annotations:1.0")
    implementation(platform("io.insert-koin:koin-bom:4.1.1"))
    implementation("io.insert-koin:koin-core:4.1.1")
    //implementation(project(":Annotation"))
    //ksp(project(":Annotation"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}