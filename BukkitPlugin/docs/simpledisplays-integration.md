# Optional SimpleDisplays API dependency

Phase 1 consumes `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT` from the independent
SimpleDisplays repository, baseline commit `21f7686`. There is no public release repository
yet. Build/release preparation must publish that reviewed source revision explicitly:

```sh
# From the JavaRailways checkout; adjust the independent source path as needed.
./gradlew -p ../SimpleDisplays publishToMavenLocal -Dmaven.repo.local=/tmp/javarailways-simpledisplays-maven
./gradlew -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-maven :BukkitPlugin:test :BukkitPlugin:shadowJar
```

The property selects a Maven repository directory or URL, restricted to the API module.
Without it the same coordinates resolve from `~/.m2/repository`. Keep source revision and
artifact provenance with release evidence: the development SNAPSHOT coordinate alone is
not immutable. Do not substitute copied API classes or the full SimpleDisplays plugin JAR.

The API is compile-only (also present only on the test classpath). The deployable host JAR
must contain no `simpledisplays/api/` classes. Install the independent SimpleDisplays plugin
on the server to provide the API identity and Bukkit `DisplayService` registration.
Generated `softdepend` orders its enable before JavaRailways; the host does not load optional
API types when the named plugin is missing or disabled. An incompatible API, missing service,
or wrong service owner disables only this integration, with a warning. Provider disable,
service unregister, and host disable release the retained service and lifecycle listener.
Re-enabling/replacing the provider requires restarting JavaRailways; no hot reconnection is
implemented. This phase creates no screens and changes no package or persistence schema.
