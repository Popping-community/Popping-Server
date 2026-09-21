# Replica sync-operation measurement

Campaign `replica-20260908-sync-probe-01`, `.tmp/replica-sync-probe/`, completed with exit code 0. Original event-consumer settings, CPU 1/1 and services restored and verified. Final GTID and row-count checks passed.

Primary/Replica 0.5/1.5 CPU, 1 GiB each, 400 VUsers; 600-second warmup, GTID/idle settling, 900-second measurement. No durability settings changed. Replica events_waits_current and events_waits_history_long are temporarily enabled. Existing timed file instruments are used; synchronization instruments remain unchanged.

Every 15 seconds collect completed binlog/redo file events (thread/event identity, operation, timer) from the 10,000-entry circular history, plus /proc/diskstats. Deduplicate by thread/event identity. These are retained event samples, not exhaustive counts or unbiased latency estimates. Disk counters cover the shared sde virtual block device, including both DBs and any other workloads. Block flush latency and MySQL sync-event latency are different measurement layers.

Validation: mocked partial consumer-enable failure restored original consumer settings successfully. The unchanged resource restoration and health gates remain active.

## Results

Tail TPS 446.07; mean 14.96ms; p95 47ms; errors 0; JIT and TPS STEADY passed. Final-five-minute replication lag 0–23s, p95 17s, ending 23s, slope +2.99s/min: UNSTABLE. Postmeasure GTID wait 61.79s.

| Retained operation samples | Count | Mean ms | p95 ms | Max ms |
|---|---:|---:|---:|---:|
| Binlog sync | 1,207 | 7.202 | 23.246 | 137.412 |
| Binlog write | 1,201 | 0.066 | 0.130 | 3.964 |
| Redo sync | 2,384 | 2.997 | 8.993 | 66.370 |
| Redo write | 3,989 | 0.067 | 0.052 | 53.330 |

These are deduplicated retained history events first observed in the final-five-minute window; they are not all operations. The arithmetic mean can exceed p95 when a few observations are large. Sampling and instrumentation overhead limit comparison with earlier runs.

Shared sde device over 284.974 seconds: 58,072 reads, 522,895 writes, average read/write request times 1.667/2.284ms, 197,857 flushes averaging 1.448ms; I/O busy 76.66%, average queue depth 5.54. This includes both DBs and other users of the device. Virtual-device busy percentage alone does not prove physical disk saturation; MySQL sync and block flush times are not one-to-one measurements.

Finding: the MySQL sync-operation category itself exhibits substantially longer sampled latency than writes. Combined with earlier cumulative worker binlog waits, this makes log synchronization a supported bottleneck candidate. It does not establish the fraction of replication lag caused by storage, distinguish host scheduling from physical storage latency, or exclude uninstrumented commit-order waits. Do not call CPU or worker tuning a complete solution based on these results.

Next useful causal comparison is equivalent durable storage with less contention while preserving workload and durability settings. A durability-relaxed experiment would answer a different question and must not be treated as an equivalent production improvement. No further load or configuration change is running.

Evidence: campaign.json, rig-state.json and sync-analysis.json in campaigns/replica-20260908-sync-probe-01; raw metrics and JTL under runs/replica-20260908-sync-probe-01-80-measure.
