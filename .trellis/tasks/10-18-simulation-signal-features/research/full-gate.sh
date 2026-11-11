#!/usr/bin/env bash
set -euo pipefail
cd /workspace/minecraft/JavaRailways/.worktree/simulation-signal-features
export JAVA_HOME=/workspace/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export GRADLE_USER_HOME=/workspace/.gradle
./gradlew :Core:test --rerun :Basic:test --rerun :SimpleATO:test --rerun :SimpleATOBukkit:test --rerun :BukkitPlugin:test --rerun :SimpleSignals:test --rerun :SimpleSignals:check :TunnelBridgeBuilder:test --rerun :WebUI:test --rerun :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :BukkitPlugin:shadowJar :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml :SimpleATO:dokkaGeneratePublicationHtml -x :WebUI:installFrontend --offline --no-daemon --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=640m'
