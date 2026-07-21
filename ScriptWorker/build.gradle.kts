plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
    id("com.gradleup.shadow") version "9.2.2"
    application
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    runtimeOnly("org.graalvm.js:js-language:24.2.2")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("work.art1st.mineRails.scriptworker.MainKt") }
kotlin { jvmToolchain(17) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
tasks.shadowJar {
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
tasks.build { dependsOn(tasks.shadowJar) }
