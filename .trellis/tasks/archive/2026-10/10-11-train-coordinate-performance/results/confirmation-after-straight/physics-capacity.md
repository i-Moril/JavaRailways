# Core headless physics capacity

> This report excludes Paper/Folia scheduling, display entities, packets, and clients.

- Git revision: `00a44abd16c284a2ec7f908a650637f70aeeebd3`
- JVM: 17.0.20 (OpenJDK 64-Bit Server VM)
- OS: Linux 6.18.54 / amd64
- CPU: Intel(R) Core(TM) Ultra 9 275HX; 8 available processors
- Max heap: 536870912 bytes
- Budget: p95 < 50.0 ms
- Matrix: 500 trains; 4 workers
- Train: 15 cars; 10.0 m/s
- Track shape: straight
- Sampling: 200 warmup epochs; 1000 measured epochs; 3 repetitions
- Shared-track spacing: 155.0 m

| Scenario | Workers | Trains | Repetition | p50 ms | p95 ms | p99 ms | Max ms | Worker max p95 ms | Worker total p95 ms | Coordinator p95 ms | Candidates/epoch | Pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|
| shared | 4 | 500 | 1 | 17.125 | 22.755 | 25.978 | 34.701 | 10.919 | 41.333 | 13.194 | 865.451 | yes |
| shared | 4 | 500 | 2 | 16.460 | 21.404 | 24.411 | 30.263 | 10.108 | 38.598 | 12.184 | 865.451 | yes |
| shared | 4 | 500 | 3 | 17.940 | 23.766 | 28.934 | 35.367 | 10.410 | 38.677 | 14.379 | 865.451 | yes |

## Maximum passing train count

- shared, 4 workers: 500
