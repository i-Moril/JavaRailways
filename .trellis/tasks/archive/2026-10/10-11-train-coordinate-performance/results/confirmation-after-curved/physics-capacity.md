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
- Track shape: curved
- Curved track: 20.0 m lateral amplitude; 500.0 m wavelength
- Sampling: 200 warmup epochs; 1000 measured epochs; 3 repetitions
- Shared-track spacing: 155.0 m

| Scenario | Workers | Trains | Repetition | p50 ms | p95 ms | p99 ms | Max ms | Worker max p95 ms | Worker total p95 ms | Coordinator p95 ms | Candidates/epoch | Pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|
| shared | 4 | 500 | 1 | 24.197 | 30.480 | 32.713 | 44.616 | 17.541 | 66.322 | 14.467 | 873.248 | yes |
| shared | 4 | 500 | 2 | 23.796 | 30.188 | 34.022 | 54.946 | 17.421 | 66.282 | 14.462 | 873.248 | yes |
| shared | 4 | 500 | 3 | 23.990 | 30.108 | 35.878 | 60.816 | 17.494 | 66.733 | 14.506 | 873.248 | yes |

## Maximum passing train count

- shared, 4 workers: 500
