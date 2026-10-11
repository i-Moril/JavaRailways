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
- Track shape: curved
- Curved track: 20.0 m lateral amplitude; 500.0 m wavelength
- Sampling: 200 warmup epochs; 1000 measured epochs; 3 repetitions
- Shared-track spacing: 155.0 m

| Scenario | Workers | Trains | Repetition | p50 ms | p95 ms | p99 ms | Max ms | Worker max p95 ms | Worker total p95 ms | Coordinator p95 ms | Candidates/epoch | Pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:---:|
| isolated | 4 | 100 | 1 | 3.213 | 4.920 | 6.302 | 10.912 | 2.217 | 7.555 | 1.749 | 0.000 | yes |
| isolated | 4 | 100 | 2 | 3.384 | 5.149 | 8.061 | 13.654 | 2.217 | 7.760 | 1.886 | 0.000 | yes |
| isolated | 4 | 100 | 3 | 3.153 | 4.674 | 5.642 | 8.405 | 2.100 | 7.342 | 1.758 | 0.000 | yes |
| isolated | 4 | 500 | 1 | 24.076 | 31.055 | 34.985 | 61.203 | 16.746 | 65.236 | 15.467 | 0.000 | yes |
| isolated | 4 | 500 | 2 | 23.996 | 31.253 | 35.068 | 62.989 | 17.263 | 66.387 | 15.408 | 0.000 | yes |
| isolated | 4 | 500 | 3 | 23.231 | 30.219 | 32.978 | 36.197 | 16.698 | 64.887 | 14.552 | 0.000 | yes |
| mixed | 4 | 100 | 1 | 2.623 | 4.119 | 5.328 | 10.906 | 1.744 | 5.923 | 1.565 | 0.000 | yes |
| mixed | 4 | 100 | 2 | 2.700 | 4.309 | 5.619 | 6.877 | 1.821 | 6.123 | 1.583 | 0.000 | yes |
| mixed | 4 | 100 | 3 | 2.558 | 3.888 | 4.847 | 7.067 | 1.912 | 5.918 | 1.507 | 0.000 | yes |
| mixed | 4 | 500 | 1 | 21.221 | 27.554 | 30.496 | 64.851 | 14.604 | 55.808 | 14.500 | 0.000 | yes |
| mixed | 4 | 500 | 2 | 20.229 | 25.971 | 28.051 | 35.427 | 13.491 | 52.765 | 13.680 | 0.000 | yes |
| mixed | 4 | 500 | 3 | 21.450 | 27.222 | 28.702 | 33.118 | 13.875 | 53.641 | 14.478 | 0.000 | yes |
| shared | 4 | 100 | 1 | 3.756 | 5.900 | 7.753 | 11.224 | 3.451 | 12.195 | 1.969 | 173.260 | yes |
| shared | 4 | 100 | 2 | 3.371 | 6.252 | 9.098 | 19.760 | 3.326 | 11.505 | 1.749 | 173.260 | yes |
| shared | 4 | 100 | 3 | 3.277 | 4.767 | 5.744 | 7.135 | 2.935 | 10.722 | 1.703 | 173.260 | yes |
| shared | 4 | 500 | 1 | 23.510 | 29.262 | 31.197 | 37.172 | 17.032 | 64.791 | 13.709 | 873.248 | yes |
| shared | 4 | 500 | 2 | 22.656 | 27.817 | 30.010 | 34.376 | 16.539 | 63.130 | 13.262 | 873.248 | yes |
| shared | 4 | 500 | 3 | 23.778 | 30.710 | 34.008 | 63.095 | 17.416 | 66.915 | 14.293 | 873.248 | yes |

## Maximum passing train count

- isolated, 4 workers: 500
- mixed, 4 workers: 500
- shared, 4 workers: 500
