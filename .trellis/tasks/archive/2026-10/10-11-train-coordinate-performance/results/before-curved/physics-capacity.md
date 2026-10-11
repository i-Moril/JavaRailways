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
| isolated | 4 | 100 | 1 | 3.228 | 4.557 | 5.919 | 7.342 | 2.176 | 7.317 | 1.561 | 0.000 | yes |
| isolated | 4 | 100 | 2 | 3.181 | 4.347 | 5.431 | 8.469 | 2.384 | 7.367 | 1.511 | 0.000 | yes |
| isolated | 4 | 100 | 3 | 3.309 | 4.314 | 5.206 | 6.490 | 2.381 | 7.403 | 1.570 | 0.000 | yes |
| isolated | 4 | 500 | 1 | 23.588 | 29.403 | 31.465 | 36.233 | 16.085 | 62.612 | 14.375 | 0.000 | yes |
| isolated | 4 | 500 | 2 | 22.581 | 28.585 | 31.857 | 63.342 | 15.686 | 61.513 | 14.915 | 0.000 | yes |
| isolated | 4 | 500 | 3 | 22.955 | 28.677 | 30.696 | 39.795 | 15.958 | 61.003 | 13.655 | 0.000 | yes |
| mixed | 4 | 100 | 1 | 2.643 | 3.612 | 5.397 | 6.821 | 1.790 | 5.593 | 1.357 | 0.000 | yes |
| mixed | 4 | 100 | 2 | 2.592 | 3.574 | 4.681 | 7.000 | 1.772 | 5.673 | 1.358 | 0.000 | yes |
| mixed | 4 | 100 | 3 | 2.492 | 3.532 | 4.389 | 5.401 | 1.721 | 5.510 | 1.310 | 0.000 | yes |
| mixed | 4 | 500 | 1 | 20.397 | 26.321 | 28.268 | 32.833 | 13.854 | 53.575 | 13.181 | 0.000 | yes |
| mixed | 4 | 500 | 2 | 20.164 | 25.927 | 29.022 | 55.029 | 13.322 | 51.438 | 13.224 | 0.000 | yes |
| mixed | 4 | 500 | 3 | 19.500 | 25.220 | 28.003 | 46.917 | 13.300 | 51.073 | 13.209 | 0.000 | yes |
| shared | 4 | 100 | 1 | 3.402 | 5.048 | 6.273 | 8.052 | 3.173 | 11.881 | 1.625 | 172.223 | yes |
| shared | 4 | 100 | 2 | 3.272 | 4.770 | 5.615 | 7.253 | 3.137 | 11.545 | 1.438 | 172.223 | yes |
| shared | 4 | 100 | 3 | 3.218 | 4.639 | 5.399 | 6.801 | 3.107 | 11.323 | 1.458 | 172.223 | yes |
| shared | 4 | 500 | 1 | 22.431 | 31.856 | 36.179 | 48.028 | 18.768 | 72.118 | 14.102 | 868.031 | yes |
| shared | 4 | 500 | 2 | 23.218 | 31.493 | 35.489 | 72.917 | 19.452 | 73.587 | 14.003 | 868.031 | yes |
| shared | 4 | 500 | 3 | 24.657 | 32.253 | 36.473 | 62.059 | 19.486 | 74.515 | 13.742 | 868.031 | yes |

## Maximum passing train count

- isolated, 4 workers: 500
- mixed, 4 workers: 500
- shared, 4 workers: 500
