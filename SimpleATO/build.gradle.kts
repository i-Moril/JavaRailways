plugins {
    id("java")
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

group = "work.art1st"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":Core"))
    compileOnly(project(":Basic"))
    compileOnly(project(":ConfigLib"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":Core"))
    testImplementation(project(":Basic"))
    testImplementation(testFixtures(project(":Core")))
    testImplementation(testFixtures(project(":Basic")))
    testImplementation(project(":ConfigLib"))
    testImplementation("org.joml:joml:1.10.8")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.register<Jar>("dokkaJavadocJar") {
    description = "Packages the SimpleATO API Dokka Javadoc output."
    dependsOn("dokkaGeneratePublicationJavadoc")
    from(layout.buildDirectory.dir("dokka/javadoc"))
    archiveClassifier.set("javadoc")
}

dokka {
    dokkaPublications.html {
        moduleName.set("JavaRailways SimpleATO API")
        includes.from("src/main/dokka/Module.md", "src/main/dokka/SimpleATOPackages.md")
        failOnWarning.set(false)
    }
    dokkaSourceSets.main {
        reportUndocumented.set(true)
        skipEmptyPackages.set(true)
        perPackageOption {
            matchingRegex.set("""work\.art1st\.javarailways\.simpleato(?!\.api(?:\.|$)).*""")
            suppress.set(true)
        }
        providers.environmentVariable("DOKKA_SOURCE_URL").orNull?.let { sourceRoot ->
            sourceLink {
                localDirectory.set(file("src/main/kotlin"))
                remoteUrl.set(uri("${sourceRoot.trimEnd('/')}/${project.name}/src/main/kotlin"))
                remoteLineSuffix.set("#L")
            }
        }
    }
}
