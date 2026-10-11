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
| shared | 4 | 500 | 1 | 21.972 | 28.165 | 32.660 | 61.202 | 16.757 | 65.283 | 12.520 | 868.031 | yes |
| shared | 4 | 500 | 2 | 22.427 | 27.818 | 29.536 | 38.190 | 16.332 | 63.660 | 12.833 | 868.031 | yes |
| shared | 4 | 500 | 3 | 23.692 | 29.865 | 33.574 | 74.956 | 17.260 | 66.260 | 13.921 | 868.031 | yes |

## Maximum passing train count

- shared, 4 workers: 500
