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
| isolated | 4 | 100 | 1 | 1.973 | 3.319 | 4.641 | 10.416 | 0.981 | 3.379 | 1.521 | 0.000 | yes |
| isolated | 4 | 100 | 2 | 1.974 | 3.266 | 4.173 | 8.227 | 0.971 | 3.348 | 1.485 | 0.000 | yes |
| isolated | 4 | 100 | 3 | 1.969 | 3.070 | 4.865 | 9.071 | 0.952 | 3.288 | 1.451 | 0.000 | yes |
| isolated | 4 | 500 | 1 | 18.422 | 23.807 | 27.194 | 34.003 | 10.609 | 39.884 | 14.496 | 0.000 | yes |
| isolated | 4 | 500 | 2 | 18.414 | 24.136 | 27.361 | 51.277 | 7.028 | 25.564 | 17.213 | 0.000 | yes |
| isolated | 4 | 500 | 3 | 18.480 | 25.158 | 28.773 | 47.325 | 8.541 | 27.247 | 17.454 | 0.000 | yes |
| mixed | 4 | 100 | 1 | 1.815 | 2.963 | 4.162 | 5.771 | 0.860 | 2.878 | 1.358 | 0.000 | yes |
| mixed | 4 | 100 | 2 | 1.814 | 2.958 | 4.534 | 8.253 | 0.874 | 2.868 | 1.372 | 0.000 | yes |
| mixed | 4 | 100 | 3 | 1.688 | 2.811 | 4.029 | 10.141 | 0.834 | 2.822 | 1.340 | 0.000 | yes |
| mixed | 4 | 500 | 1 | 17.442 | 22.816 | 25.631 | 28.926 | 9.367 | 34.665 | 14.707 | 0.000 | yes |
| mixed | 4 | 500 | 2 | 17.530 | 23.426 | 26.048 | 32.837 | 9.244 | 32.448 | 16.474 | 0.000 | yes |
| mixed | 4 | 500 | 3 | 16.672 | 20.863 | 24.383 | 26.441 | 8.188 | 24.041 | 13.582 | 0.000 | yes |
| shared | 4 | 100 | 1 | 2.038 | 3.099 | 4.129 | 6.620 | 1.061 | 3.706 | 1.472 | 171.714 | yes |
| shared | 4 | 100 | 2 | 2.101 | 3.232 | 4.691 | 6.325 | 1.025 | 3.561 | 1.514 | 171.714 | yes |
| shared | 4 | 100 | 3 | 2.244 | 3.653 | 4.899 | 10.530 | 1.095 | 3.878 | 1.577 | 171.714 | yes |
| shared | 4 | 500 | 1 | 15.740 | 20.595 | 22.783 | 30.289 | 9.820 | 37.890 | 12.058 | 865.451 | yes |
| shared | 4 | 500 | 2 | 17.994 | 24.499 | 27.085 | 30.552 | 6.867 | 25.910 | 17.890 | 865.451 | yes |
| shared | 4 | 500 | 3 | 18.275 | 24.508 | 29.731 | 50.844 | 10.946 | 42.007 | 14.136 | 865.451 | yes |

## Maximum passing train count

- isolated, 4 workers: 500
- mixed, 4 workers: 500
- shared, 4 workers: 500
