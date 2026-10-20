#!/usr/bin/env bash
set -euo pipefail
cd /workspace/minecraft/JavaRailways/.worktree/simulation-world-alignment
export JAVA_HOME=/workspace/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export GRADLE_USER_HOME=/workspace/.gradle
./gradlew :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :SimpleSignals:test :SimpleSignals:check :TunnelBridgeBuilder:test :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :BukkitPlugin:shadowJar :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml :SimpleATO:dokkaGeneratePublicationHtml --offline --no-daemon --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=640m' -x :WebUI:installFrontend
