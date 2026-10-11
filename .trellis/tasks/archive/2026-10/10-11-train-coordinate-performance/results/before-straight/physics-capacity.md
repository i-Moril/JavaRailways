# Core headless physics capacity

> This report excludes Paper/Folia scheduling, display entities, packets, and clients.

- Git revision: `00a44abd16c284a2ec7f908a650637f70aeeebd3`
- JVM: 17.0.20 (OpenJDK 64-Bit Server VM)
- OS: Linux 6.18.54 / amd64
- CPU: Intel(R) Core(TM) Ultra 9 275HX; 8 available processors
- Max heap: 536870912 bytes
- Budget: p95 < 50.0 ms
- Matrix: 100, 500 trains; 4 workers
- Train: 15 cars; 10.0 m/s
- Track shape: straight
- Sampling: 200 warmup epochs; 1000 measured epochs; 3 repetitions
- Shared-track spacing: 155.0 m

| Scenario | Workers | Trains | Repetition | p50 ms | p95 ms | p99 ms | Max ms | Worker max p95 ms | Worker total p95 ms | Coordinator p95 ms | Candidates/epoch | Pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|
| isolated | 4 | 100 | 1 | 3.064 | 4.355 | 5.711 | 8.481 | 1.999 | 6.902 | 1.480 | 0.000 | yes |
| isolated | 4 | 100 | 2 | 3.062 | 4.130 | 5.740 | 8.267 | 2.214 | 6.887 | 1.481 | 0.000 | yes |
| isolated | 4 | 100 | 3 | 2.971 | 4.236 | 5.438 | 8.976 | 2.016 | 7.101 | 1.495 | 0.000 | yes |
| isolated | 4 | 500 | 1 | 22.340 | 28.759 | 31.468 | 60.903 | 16.322 | 63.373 | 13.401 | 0.000 | yes |
| isolated | 4 | 500 | 2 | 22.217 | 28.321 | 30.749 | 32.953 | 16.056 | 62.448 | 13.553 | 0.000 | yes |
| isolated | 4 | 500 | 3 | 21.991 | 28.132 | 30.379 | 34.642 | 15.734 | 61.604 | 12.885 | 0.000 | yes |
| mixed | 4 | 100 | 1 | 2.498 | 3.932 | 6.701 | 13.886 | 1.621 | 5.379 | 1.273 | 0.000 | yes |
| mixed | 4 | 100 | 2 | 2.544 | 3.369 | 4.501 | 5.483 | 1.512 | 5.208 | 1.312 | 0.000 | yes |
| mixed | 4 | 100 | 3 | 2.643 | 3.923 | 4.973 | 7.020 | 1.781 | 5.529 | 1.338 | 0.000 | yes |
| mixed | 4 | 500 | 1 | 18.823 | 24.148 | 26.994 | 29.789 | 12.666 | 49.861 | 12.485 | 0.000 | yes |
| mixed | 4 | 500 | 2 | 19.294 | 24.605 | 27.302 | 31.744 | 12.830 | 50.208 | 13.024 | 0.000 | yes |
| mixed | 4 | 500 | 3 | 19.322 | 24.699 | 27.767 | 34.642 | 12.918 | 50.444 | 12.880 | 0.000 | yes |
| shared | 4 | 100 | 1 | 3.223 | 4.601 | 5.996 | 12.409 | 3.088 | 11.225 | 1.536 | 172.223 | yes |
| shared | 4 | 100 | 2 | 3.191 | 4.437 | 5.098 | 7.116 | 2.944 | 11.040 | 1.474 | 172.223 | yes |
| shared | 4 | 100 | 3 | 3.324 | 4.735 | 5.787 | 7.877 | 3.111 | 11.184 | 1.368 | 172.223 | yes |
| shared | 4 | 500 | 1 | 20.387 | 25.856 | 28.616 | 57.405 | 15.131 | 58.876 | 11.613 | 868.031 | yes |
| shared | 4 | 500 | 2 | 19.902 | 25.011 | 27.135 | 31.262 | 14.824 | 57.779 | 11.072 | 868.031 | yes |
| shared | 4 | 500 | 3 | 21.021 | 26.716 | 28.723 | 31.031 | 15.400 | 58.929 | 11.856 | 868.031 | yes |

## Maximum passing train count

- isolated, 4 workers: 500
- mixed, 4 workers: 500
- shared, 4 workers: 500
