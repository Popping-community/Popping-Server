# Commit-order 대기 계측 결과

`replica-20260909-commit-probe-01`을 2026-09-09 **02:45~03:18 KST** 실행해 종료 코드 0으로 완료했다. 600초 워밍업 1회, GTID 확인, 167.88초 idle 안정화, 900초 본 측정 1회를 수행했다. 07:43 KST 별도 읽기 전용 최종 검증도 통과했다. 추가 실험은 실행하지 않았다.

**이번 관측에서 worker의 명시적인 커밋 차례 대기는 작았고, handler commit 상태와 로그 파일 대기는 상당했다.** Coordinator의 의존성 대기도 확인했다. 이는 커밋 경로·로그 동기화를 계속 조사할 근거지만, 저장소가 복제 지연의 단독 원인이라는 뜻은 아니다. 커밋 순서 유지나 group commit의 모든 영향을 배제하지도 않는다.

## 성능과 복제 지연

요청 오류·invalid row·redirect child 오류 모두 0, JIT·coverage·TPS STEADY 통과. 본 측정에서 분석 대상 요청은 390,557개였다. 아래 응답 성능은 마지막 **120초 `[780,900)`**, 복제 판정은 마지막 **5분 `[600,900)`** 기준이다.

| 지표 | 결과 |
|---|---:|
| Tail TPS | 443.325 |
| Tail 평균 / p50 | 18.856 / 8 ms |
| Tail p95 / p99 | 57 / 162 ms |
| 복제 lag 최소 / 최대 / 종료 | 0 / 26 / 26초 |
| 복제 lag p95 | 20초 |
| 복제 lag 기울기 | +3.387초/분 |
| 첫·끝 1분 lag 평균 차이 | +15.5초 |
| 복제 안정 판정 | **UNSTABLE** |
| 측정 후 GTID 확인 단계 대기 | 76.867초 |

워밍업 뒤 GTID 확인은 1.03초였으나 본 측정 중 다시 지연이 누적됐다. TPS STEADY는 응답시간 안정성을 뜻하지 않는다. 마지막 2분의 평균은 각각 19.31/18.40ms로, 앞선 분들의 약 10~11ms보다 높았다. 76.867초는 GTID 확인 단계의 대기 시간으로, 마지막 요청 완료부터의 전체 회복 시간과 구분한다.

## 스레드별 누적 대기

마지막 5분 안의 20개 표본, 실제 첫~마지막 관측 **608.963~894.029초, 285.066초**를 사용했다. 중간 표본까지 thread identity·counter reset을 검사했으며 이상이 없었다. 독립 재계산도 일치했다.

| 계측 항목 | Worker 1 | Worker 2 | Coordinator |
|---|---:|---:|---:|
| 선행 커밋 차례 대기 | 0.182초 (0.064%) | 0.814초 (0.285%) | 0 |
| Handler commit 상태 | 172.293초 (60.440%) | 151.444초 (53.126%) | 5.219초 (1.831%) |
| 의존 트랜잭션 완료 대기 | 0 | 0 | 88.481초 (31.039%) |
| Binlog Group Commit ticket 대기 | 0 | 0 | 0 |
| Binlog 파일 대기 | 86.840초 (30.463%) | 63.977초 (22.443%) | 수집 대상 아님 |
| InnoDB 데이터 파일 대기 | 5.718초 (2.006%) | 5.139초 (1.803%) | 수집 대상 아님 |

괄호는 **각 스레드의 누적 시간 / 285.066초**다. CPU 사용률이나 시스템 전체 병목 비중이 아니다. 스레드끼리 동시에 대기하며, stage와 내부 file wait도 겹치므로 합산하거나 차감해 독립 원인별 시간을 만들지 않는다. Handler commit은 해당 상태로 표시된 누적 시간이며 순수 fsync나 함수 전체 실행시간과 같지 않다.

선행 커밋 대기가 작고 coordinator 의존성 대기가 큰 것은 모순이 아니다. 다음 트랜잭션 배정이 선행 작업 완료를 기다릴 수 있다. 이번 결과만으로 worker 수 증가나 preserve_commit_order 변경의 효과를 확정할 수 없다.

## 보존된 로그 작업 표본

Replica의 순환 history에서 중복 제거 후 마지막 5분에 **처음 관측된 이벤트**다. Worker 외의 Replica 스레드도 포함한다. 전체 작업 분포나 편향 없는 추정치가 아니며 이벤트 시작·종료 시각으로 구간을 엄밀히 자른 것도 아니다.

| 작업 | 표본 수 | 평균 ms | p95 ms | 최대 ms |
|---|---:|---:|---:|---:|
| Binlog sync | 1,224 | 6.730 | 23.167 | 126.344 |
| Binlog write | 1,222 | 0.064 | 0.125 | 8.674 |
| Redo sync | 2,405 | 2.878 | 10.222 | 58.172 |
| Redo write | 3,998 | 0.040 | 0.044 | 10.064 |

공유 가상 장치 `sde`의 같은 관측 간격에서 read/write await는 2.332/2.823ms, flush await 1.593ms, busy 77.118%, 평균 queue depth 6.570이었다. 양 DB와 다른 작업을 포함하며 물리 SSD 포화를 입증하지 않는다. MySQL sync 시간과 블록 장치 flush 시간은 서로 다른 계층이다.

직전 sync 계측은 TPS 446.07, 평균 14.96ms, p95 47ms, lag 최대 23초였다. 이번은 443.33 TPS, 평균 18.86ms, p95 57ms, lag 최대 26초다. Binlog sync 표본 평균은 7.20→6.73ms지만 p95는 23.25→23.17ms로 비슷했다. 튜닝 변경 없이 계측만 추가한 단일 실행 간 차이이므로 개선·악화 효과로 단정하지 않는다.

다음 인과 비교 후보는 내구성 1/1을 유지하면서 저장소 경합을 줄일 수 있는 별도 환경이다. 이전 조사에서는 연결된 SSD가 하나여서 물리 분리가 불가능했다. 단순히 volume 이름을 나누거나 내구성을 완화하는 것으로 같은 비교를 대신하지 않는다. 위 단회 계측 외에 저장소·내구성·worker 설정 변경이나 추가 부하 실험은 수행하지 않았다.

## 원복과 검증

- 하네스 finally 원복 완료: Primary/Replica 각 **1 CPU/1 GiB**, 원래 22개 컨테이너 ID·실행 상태·CPU·메모리·restart policy 일치. 임시 컨테이너와 owner label 잔존 없음. FinBound 9개와 renderer는 중지 상태를 유지했고, Grafana/Prometheus는 원래 중지 상태로 복구했다.
- Stage 4개의 ENABLED/TIMED 및 current/history_long consumer 4개는 원래 **NO**로 복구. 내구성 1/1, Replica worker 2·preserve_commit_order ON 유지.
- 07:43 KST 직접 확인: 앱 8081/8082 health UP, 복제 coordinator/connection/worker ON·오류 0, Primary GTID의 Replica 포함 검사 통과.
- Primary/Replica 행 수 각각 **post 1,261,828 / comment 5,261,024 / likes 7,354,639** 일치. 전체 내용 checksum이나 측정 중 read-after-write 정합성 검증은 아니다. 최종 행 수는 서비스 복구 뒤 순차 조회값이다.
- 본 측정 중 최소 가용 물리 **3.256 GiB**, 커밋 여유 **6.122 GiB**. 안전 기준 미달 없음. 입력 파일 24개 hash 재검증 일치.
- 기존 계측·분석 테스트 6개, 단회 워밍업 성공/실패 복구 테스트 2개 통과. 최종 검증 도구의 설정/health 성공·실패 모의 검증도 통과했다. 사용자 소스·README·기존 실험 원본은 보존했고 커밋하지 않았다.

원본은 `.tmp/replica-commit-probe/campaigns/replica-20260909-commit-probe-01/`의 `campaign.json`, `rig-state.json`, `commit-analysis.json`, `sync-analysis.json`, `final-verification.json`, `input-and-safety-audit.json`과 같은 하네스 `runs/replica-20260909-commit-probe-01-*`다.

### 2026-09-09 재개 사전 확인

사용자가 IntelliJ 저장·종료를 완료했다고 응답했으며 프로세스 종료를 직접 확인했다. 02:42 KST 새 읽기 전용 사전 검사에서 가용 물리 8.10 GiB, 커밋 여유 9.13 GiB였다. 소스·고정 이미지·fixture hash, DB 각 1 CPU/1 GiB 원상태, 복제 ON/오류 0/GTID catch-up, worker 2개, preserve_commit_order ON, 내구성 1/1을 재확인했다. Worker 1/2와 coordinator의 INSTRUMENTED/HISTORY 및 global/thread instrumentation도 YES였다.

새 사전 검사 원본은 `.tmp/replica-commit-probe/readiness/20260908T174222122333Z/`에 별도로 보존했다. 기존 preflight와 완료 실험은 덮어쓰지 않았다. 최종 원복 검증 도구는 이번 campaign 이름을 명시하도록 수정하고 stage instrument 및 전체 원래 컨테이너 상태 검증을 추가했다. 이번 워밍업은 600초 1회로 제한하며 JIT 미통과 시 추가 실행 없이 실패 처리하고 원복한다. 본 측정도 900초 1회다.

## 조건과 판단 기준

- MySQL 8.4.9, 기존 B 구성: App 2대, write Primary/read Replica, sticky routing 유지.
- 부하 400 VUser, Primary/Replica 0.5/1.5 CPU 및 각 1 GiB. 기존 이미지·fixture·JMX 비율·pool 유지.
- worker 2개, preserve_commit_order ON, sync_binlog=1, innodb_flush_log_at_trx_commit=1 유지.
- 600초 워밍업, GTID catch-up, 기존 idle 안정화, 900초 본 측정 1회. 기존 JIT 및 안전 기준 유지.
- worker와 coordinator의 단계별 누적 시간 차이를 주지표로 사용한다. worker ID 0은 하네스에서 coordinator를 나타내는 표기다.
- 활성화 stage: `Waiting for preceding transaction to commit`, `Waiting for dependent transaction to commit`, `waiting for handler commit`, `Waiting for Binlog Group Commit ticket` (모두 `stage/sql/` 접두사).
- events_stages_current/history_long 및 기존 events_waits_current/history_long만 활성화한다. stage instrument의 ENABLED/TIMED 원래 값도 journal에 먼저 기록하고 finally에서 원복한다.
- mutex/cond 계측 및 worker 재시작은 하지 않는다. 기존 객체에 동적 활성화가 적용되지 않는 문제를 피하고 의미가 명확한 stage 계측에 집중한다.
- 15초 주기로 stage 누적·history, 기존 worker file waits, binlog/redo operation 표본 및 diskstats를 수집한다. history는 순환 버퍼의 보존 표본으로 전체 분포가 아니다.
- 누적값 차이는 thread identity 및 counter reset 여부를 검사한다. 마지막 5분 내 첫/마지막 관측 사이의 실제 시간을 명시한다.
- 선행 커밋 대기는 다른 worker가 순서를 기다리는 근거다. 선행 worker가 느린 원인까지 확정하지 않는다. handler commit으로 표시된 단계는 순수 fsync 시간이 아니다. stage와 내부 file wait는 중복되므로 합산하지 않는다.

## 사전 확인 및 안전

하네스 `.tmp/replica-commit-probe/`는 완료된 sync 하네스에서 별도로 복사했다. 이전 캠페인·결과는 보존한다. 사전 조회에서 복제 ON/오류 0/GTID catch-up, 고정 소스·이미지 일치를 확인했다.

준비 당시 IntelliJ가 약 2.2 GiB를 사용하고 가용 물리 메모리가 약 4.4 GiB여서, 과거 메모리 부족 중단 이력을 고려해 사용자에게 저장·종료를 요청했다. 재개 시 완료 응답과 메모리 재확인 후 위 실험을 시작했다.

부분 stage/consumer 활성화 실패 및 원래 혼합 상태 복구, thread 교체·counter reset 거부, coordinator 구분 테스트 총 6개 통과. 기존 서비스 exact ID·CPU/메모리·실행 상태 journal 및 finally 복구를 유지한다. 실제 worker/coordinator 계측 SQL 조회도 통과했다.

## 정확한 버전의 공식 근거

2026-09-09 실행 대기 중 GitHub connector로 아래 `mysql-8.4.9` 소스를 다시 읽어 해석 범위를 확인했다.

- **Preceding transaction:** `Commit_order_manager::wait_on_graph()`에서 자신이 큐 선두가 아닐 때 차례 대기에 붙는 stage다. 작은 값은 해당 명시적 대기가 작다는 뜻이며 큐 선두 자체의 커밋 비용이나 순서 유지의 전체 영향을 배제하지 않는다. [공식 소스](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/rpl_replica_commit_order_manager.cc#L73-L124)
- **Dependent transaction:** coordinator가 논리적 의존성 완료 지점까지 기다리며 다음 트랜잭션 배정 전에 호출하는 경로다. 큰 값은 선행 작업 완료를 기다린 근거지만 coordinator 자체의 CPU 병목이나 선행 작업이 느린 원인을 확정하지 않는다. [대기 함수](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/rpl_mta_submode.cc#L519-L554), [호출부](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/rpl_mta_submode.cc#L684-L705)
- **Handler commit:** `ha_commit_trans()` 진입에서 설정한 뒤 GTID 처리·잠금·prepare·commit 호출 등이 이어지는 넓은 상태다. 순수 fsync 시간도, 함수 전체 실행시간과 정확히 같은 값도 아니다. 보고서에서는 해당 상태로 표시된 누적 stage 시간으로 부른다. Stage 이벤트 횟수는 트랜잭션 수와 같지 않다. [공식 소스](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/handler.cc#L1634-L1885)
- **Binlog Group Commit ticket:** mutex 획득 후 현재 ticket 차례를 기다리는 조건 대기 구간이다. 최초 mutex 획득 대기와 follower의 별도 leader 대기는 포함하지 않으므로 0이어도 group commit 관련 전체 대기를 배제하지 않는다. [ticket 대기](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/rpl_commit_stage_manager.cc#L186-L215), [별도 follower 대기](https://github.com/mysql/mysql-server/blob/mysql-8.4.9/sql/rpl_commit_stage_manager.cc#L345-L370)
