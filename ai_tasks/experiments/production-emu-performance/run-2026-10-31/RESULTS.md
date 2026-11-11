# Formal production actuator measurement — 2026-10-31

Intel Core Ultra 9 275HX, OpenJDK 25.0.4, G1, 512 MiB heap. Three independent JVMs; each of six cases receives 2,400 warm-up and 4,800 measured 50 ms frames. The compile receipt verifies source-to-class provenance and runtime inputs before/after each fork. HEAD identifies the baseline; the receipt identifies the actual dirty candidate sources.

For 16 cars, the median across JVMs of mean actuation-only cost is **8.855 µs/frame**, p95 **19.146 µs**, p99 **20.622 µs**, allocation **5,463.6 bytes/frame**. Including production braking-envelope construction, permitted-speed query, spatial candidate admission and actuation gives mean **40.020 µs/frame**, p95 **115.118 µs**, p99 **125.678 µs**, allocation **16,571.0 bytes/frame**. Each JVM/case recorded at least 3,284 moving frames out of 4,800; 16-car maximum speed was 13.438 m/s.

These synthetic heterogeneous equipment scenarios measure the isolated production actuator with fixed Core geometry, not full-world tick capacity or calibrated S100 performance. No earlier prototype percentage is used as a production comparison. See summary.json and raw per-fork CSV for 4/8/16-car measurements and variation.

Coordinated benchmark compilation, Basic HTML Dokka and refreshed Bukkit shadowJar succeeded in 43 seconds. The final candidate passed all 13 equipment-migration tests, including real source/candidate JAR codecs, in 18.486 seconds. Candidate hashes and complete logs are included. Tests used temporary local fixtures; no server data was changed or deployment performed.
