# Replica worker 진단 (2026-09-08)

20:29 KST 읽기 전용 조회 결과 MySQL 8.4.9, Replica worker 2개, LOGICAL_CLOCK, replica_preserve_commit_order ON이다. 두 worker 모두 ON/오류 0이며 현재는 Coordinator 이벤트를 기다리고 있다. GTID catch-up도 통과했다. 유휴 상태 조회이므로 부하 중 worker 활용률이나 worker 부족 여부는 확인하지 못했다.

Primary와 Replica의 innodb_buffer_pool_size는 각각 134217728 bytes(128 MiB), 컨테이너 메모리는 각각 1 GiB다. replica_pending_jobs_size_max도 128 MiB다. sync_binlog 및 innodb_flush_log_at_trx_commit은 양쪽 모두 1이다.

기존 측정 마지막 2분 내 cgroup 첫/마지막 표본 차이로 산출한 Replica 읽기량은 1/1 CPU에서 2.91 MiB/s, 0.5/1.5 CPU에서 2.98 MiB/s다. 쓰기량은 각각 8.81, 7.34 MiB/s다. 이 값만으로 디스크 포화나 버퍼 풀 부족을 확정할 수 없다. 이전 측정에는 worker별 활동 표본이 없어 병렬 적용 병목을 소급 판정할 수 없다.

다음 실험은 0.5/1.5 CPU와 worker 2개를 기준으로 부하 중 worker별 적용 transaction 변화·대기 상태와 버퍼 풀 읽기 counter를 수집하는 것이 우선이다. worker 부족 근거 없이 4개로 늘린 결과를 개선책으로 해석하지 않는다. 기준선 진단 결과에 따라 worker 수 또는 버퍼 풀 크기 중 한 가지만 바꿔 비교한다.

이번 진단에서는 서비스·CPU·MySQL 설정을 변경하거나 부하를 발생시키지 않았다. 원본: `.tmp/replica-reallocation-05-15/worker-diagnosis.json`.
