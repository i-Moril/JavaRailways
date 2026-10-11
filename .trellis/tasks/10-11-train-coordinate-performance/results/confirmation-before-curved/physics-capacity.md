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
| shared | 4 | 500 | 1 | 22.610 | 29.941 | 32.549 | 52.105 | 17.708 | 66.165 | 13.443 | 868.031 | yes |
| shared | 4 | 500 | 2 | 21.222 | 29.583 | 41.832 | 80.965 | 16.929 | 64.309 | 14.105 | 868.031 | yes |
| shared | 4 | 500 | 3 | 20.457 | 31.702 | 45.295 | 77.151 | 17.012 | 65.932 | 15.064 | 868.031 | yes |

## Maximum passing train count

- shared, 4 workers: 500
