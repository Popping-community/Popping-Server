# Worker / buffer pool diagnostic run

Campaign: `replica-20260908-worker-probe-01`, harness `.tmp/replica-worker-probe/`.

Status: completed, exit code 0. Original CPU allocation (1/1) and services restored and verified; final GTID catch-up and row counts passed. No temporary containers remain according to the restoration journal.

Conditions: Primary 0.5 CPU, Replica 1.5 CPU, 1 GiB each; B topology, 400 VUsers, 600-second warmup, GTID catch-up and idle settling, 900-second measurement. No MySQL settings changed.

Additional 15-second observations: each worker's last/applied transaction and thread state; Replica buffer pool logical/physical reads, wait-free counter, dirty pages and pending data reads/writes. Worker transaction ID changes establish observed progress, not transaction counts; instantaneous state sample fractions are not precise utilization measurements. Additional SQL polling introduces some observation overhead compared with the previous run.

Evidence: `campaigns/replica-20260908-worker-probe-01/campaign.json`, `worker-analysis.json`, and `runs/replica-20260908-worker-probe-01-80-measure/metrics.jsonl` under the harness directory.

## Results

Tail TPS 445.55; mean 12.11 ms; p95 37 ms; p99 91 ms. Errors 0, JIT and TPS STEADY passed. Final-five-minute lag 0–13 seconds, p95 8 seconds, ending 13 seconds, slope +1.31 seconds/minute: UNSTABLE. Postmeasurement GTID wait 61.85 seconds.

The original campaign verdict is INCONCLUSIVE because the existing gate compares the entire health dictionary for equality, and the probe added two fields. Offline reanalysis projected the original four health fields without changing their values or any thresholds. The corrected verdict is stored separately in worker-analysis.json; original evidence is unchanged.

Across 20 samples in the final five minutes, both workers' last-applied transactions changed in all 19 adjacent sample pairs. Worker 1 showed handler-commit waiting in 11 samples, worker 2 in 10; both showed coordinator-event waiting in 9. This does not establish worker-count shortage. Sparse simultaneous snapshots cannot measure exact busy time or identify the specific commit wait cause.

Replica physical buffer reads increased by 57,492 (201.89/s); logical read requests by 42,670,041. Physical/logical ratio 0.1347%; wait-free delta 0. Pending reads peaked at 5 and writes at 1. These observations do not prove disk saturation or exclude I/O latency. A high aggregate cache hit rate also does not exclude costly individual misses.

Next diagnostic priority: per-worker wait-event timing and redo/binlog file wait timing under load, to distinguish commit-order, storage, and other commit-stage waiting before changing worker count or durability settings. This run changed no MySQL settings. Improved lag versus the previous identical allocation is run-to-run variation, not evidence of a tuning improvement.
