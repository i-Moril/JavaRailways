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
| isolated | 4 | 100 | 1 | 2.349 | 3.210 | 4.066 | 5.294 | 1.730 | 6.043 | 1.072 | 0.000 | yes |
| isolated | 4 | 100 | 2 | 2.372 | 3.092 | 4.213 | 7.129 | 1.508 | 5.408 | 1.121 | 0.000 | yes |
| isolated | 4 | 100 | 3 | 2.271 | 3.099 | 4.737 | 7.914 | 1.596 | 5.499 | 1.169 | 0.000 | yes |
| isolated | 4 | 500 | 1 | 14.643 | 19.037 | 21.098 | 41.552 | 10.876 | 41.695 | 8.143 | 0.000 | yes |
| isolated | 4 | 500 | 2 | 15.724 | 20.304 | 21.873 | 25.144 | 11.615 | 44.602 | 8.459 | 0.000 | yes |
| isolated | 4 | 500 | 3 | 15.651 | 20.097 | 22.473 | 45.633 | 11.752 | 45.633 | 8.672 | 0.000 | yes |
| mixed | 4 | 100 | 1 | 2.032 | 2.909 | 3.623 | 4.710 | 1.382 | 4.856 | 1.064 | 0.000 | yes |
| mixed | 4 | 100 | 2 | 1.998 | 2.759 | 3.256 | 4.584 | 1.222 | 4.187 | 1.014 | 0.000 | yes |
| mixed | 4 | 100 | 3 | 2.019 | 2.946 | 3.830 | 5.334 | 1.259 | 4.166 | 1.007 | 0.000 | yes |
| mixed | 4 | 500 | 1 | 13.787 | 17.747 | 20.722 | 40.552 | 9.351 | 35.090 | 9.030 | 0.000 | yes |
| mixed | 4 | 500 | 2 | 13.437 | 17.373 | 18.685 | 20.716 | 9.391 | 36.219 | 8.010 | 0.000 | yes |
| mixed | 4 | 500 | 3 | 14.366 | 20.848 | 24.059 | 30.127 | 12.542 | 46.581 | 8.778 | 0.000 | yes |
| shared | 4 | 100 | 1 | 2.479 | 3.472 | 4.105 | 5.435 | 1.719 | 6.275 | 1.076 | 172.223 | yes |
| shared | 4 | 100 | 2 | 2.733 | 4.690 | 6.728 | 11.886 | 2.688 | 7.534 | 1.140 | 172.223 | yes |
| shared | 4 | 100 | 3 | 2.511 | 3.262 | 4.127 | 4.980 | 1.713 | 5.949 | 1.068 | 172.223 | yes |
| shared | 4 | 500 | 1 | 14.482 | 18.327 | 19.621 | 21.684 | 10.886 | 42.161 | 7.535 | 868.031 | yes |
| shared | 4 | 500 | 2 | 14.221 | 18.112 | 19.840 | 41.814 | 11.091 | 42.351 | 7.013 | 868.031 | yes |
| shared | 4 | 500 | 3 | 17.099 | 22.201 | 23.338 | 26.934 | 13.260 | 50.259 | 9.225 | 868.031 | yes |

## Maximum passing train count

- isolated, 4 workers: 500
- mixed, 4 workers: 500
- shared, 4 workers: 500
