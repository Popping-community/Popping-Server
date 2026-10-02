# Replica 저장 경로 및 로그 설정 진단

추가 확인: Primary도 `/dev/sde` ext4로 표시된다. 두 named volume은 컨테이너에서 동일한 블록 장치를 공유한다. 호스트 물리 디스크의 상세 구성은 이번 조회로 확인하지 않았다.

2026-09-08 22:32 KST 읽기 전용 조회. 원본 `.tmp/replica-wait-probe/storage-probe.json`.

Replica 데이터 디렉터리는 Docker local named volume `popping-server_mysql-replica-data`이며 `/var/lib/mysql`에 마운트되어 있다. 컨테이너에서 확인한 파일시스템은 `/dev/sde`, ext4, 사용률 6%다. 데이터·binlog·redo 파일 모두 이 경로 아래에 있다. Windows 소스 디렉터리를 DB 데이터에 직접 bind mount한 구성은 아니다. 컨테이너의 overlay2 표시는 데이터 볼륨의 파일시스템과 구분한다.

Primary도 별도 local named volume을 사용한다. Replica는 `log_bin=ON`, `log_replica_updates=ON`, `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1`, `innodb_flush_method=O_DIRECT`, redo capacity 100 MiB다. Primary의 해당 값도 동일하다. 따라서 Replica는 읽기와 복제 적용 외에 자신의 binlog 기록 경로도 사용한다. 이전 부하 측정에서 worker의 binlog 대기가 관측된 사실과 일치한다.

디스크 용량 부족 근거는 없다. 단일 /proc/diskstats 스냅샷은 부하 구간의 지연이나 포화를 입증하지 않는다. 이전 binlog misc 평균 5.39ms와 redo misc 평균 2.26ms를 fsync만의 지연으로 단정하지 않는다.

정확한 파일 OPERATION별 이벤트를 소급 확인할 수 없는 이유도 확인했다. events_waits_current/history/history_long consumer가 모두 OFF이며, history 용량은 thread별 10개/global 10,000개다. 따라서 기존 누적 파일 통계의 misc를 과거 작업별로 분해할 수 없다.

다음에 원인을 더 좁히려면 이벤트 consumer를 임시 활성화하고 원복할 저널을 둔 상태에서 파일 OPERATION별 표본과 /dev/sde diskstats 구간 차이를 부하와 함께 수집해야 한다. 이벤트 history는 순환 버퍼이므로 전수 집계로 해석하면 안 된다. 이번에는 MySQL·CPU·계측 설정을 변경하거나 새로운 부하를 실행하지 않았다. 복제 ON/오류 0/GTID catch-up을 확인했다.
