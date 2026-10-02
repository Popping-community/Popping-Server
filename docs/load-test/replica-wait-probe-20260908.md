# Replica file-wait probe

Campaign `replica-20260908-wait-probe-01` completed under `.tmp/replica-wait-probe/`, exit code 0. CPU 1/1 and original services restored, final GTID and row-count checks passed.

Same Primary/Replica 0.5/1.5 CPU, 1 GiB each, 400 VUsers, 600-second warmup, idle/GTID gates, 900-second measurement. No MySQL settings or instrumentation settings changed. Adds 15-second cumulative per-worker wait and global log-file operation counters. Interpret interval deltas only. Global log-file timers include concurrent/background threads and must not be summed with per-worker waits as independent time. Misc file operations include sync/open/close and do not establish fsync-specific latency alone. Commit-order instruments are disabled, so this run cannot quantify that component separately.

The health gate now checks the four required health fields and permits additional probe fields. Thresholds are unchanged. CPU and original services restore in finally.

## Results

400 VUsers: tail 443.84 TPS, mean 17.26 ms, p95 60 ms, errors 0, JIT and TPS STEADY passed. Final-five-minute lag 0–24 seconds, p95 18, ending 24, slope +2.70 seconds/minute: UNSTABLE. Postmeasure catch-up wait 61.85 seconds.

Across the final-five-minute sampled interval (285.207 seconds), worker 1 accumulated 80.204 seconds of binlog-file waiting and worker 2 62.510 seconds. These are 28.1% and 21.9% of the interval per worker, not percentages of total measured waits or overall CPU utilization. Their respective data-file waits were only 5.311 and 4.528 seconds among enabled instruments. Disabled synchronization instruments leave unmeasured wait categories.

Global binlog file counters: 26,273 writes took 1.382 seconds total (0.0526 ms/op); 26,232 misc operations took 141.507 seconds (5.394 ms/op). Redo log file: 84,132 writes took 3.373 seconds (0.0401 ms/op); 52,512 misc operations took 118.668 seconds (2.260 ms/op). Global file and worker times overlap; do not add them as independent delays. Misc is not an fsync-only category. No counter resets or worker identity changes appeared between interval endpoints.

Conclusion: log-file operations outside ordinary writes account for substantial observed delay while replication lag accumulates. This supports investigating log synchronization/storage latency before increasing worker count. It does not isolate fsync, prove storage saturation, or rule out commit-order waiting. No durability or replication settings were weakened. A follow-up should isolate file operation types or test equivalent durable storage while retaining sync_binlog=1 and innodb_flush_log_at_trx_commit=1; avoid presenting a durability-relaxed run as equivalent production performance.

Evidence: campaign.json, rig-state.json and wait-analysis.json under the campaign directory; metrics.jsonl and analysis.json under the measure run. Added polling overhead and run-to-run variability prevent treating changes from the prior run as tuning effects.
