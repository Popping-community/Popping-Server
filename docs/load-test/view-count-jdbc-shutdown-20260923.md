# 실제 JDBC 작업 중 SIGTERM과 조회수 저장 — 2026-09-23

## 확인한 결과

현재 수정된 Popping 앱을 격리 MySQL과 실행했다. 게시글 상세 GET 한 번으로 생긴 조회수 증가가 실제 UPDATE에 인계되어 행 잠금 대기 중임을 확인한 뒤, JVM에 SIGTERM을 보냈다. **종료 코드 143과 HTTP graceful shutdown 완료 로그가 있어도 조회수 저장은 완료되지 않을 수 있었다.**

| 조건 | Spring phase 기한 | Docker 유예 | 잠금 해제 | 최종 DB 조회수 | 신호 / 종료 코드 |
|---|---:|---:|---|---:|---|
| release-in-time | 5초 | 15초 | stop CLI 호출 약 1초 후, 앱 종료 전 | 1 | SIGTERM / 143 |
| phase-timeout | 1초 | 15초 | 앱 종료 후 | 0 | SIGTERM / 143 |
| docker-deadline | 5초 | 1초 | 앱 종료 후 | 0 | SIGTERM → SIGKILL / 137 |

각 조건 **1회**, 조회수 증가 **1건**이다. 유실률·성공률·성능 개선율·운영 종료 예산을 추정하는 표본이 아니다. phase 기한은 단계별 값이며 전체 종료 시간의 엄격한 상한이 아니다. MySQL lock wait timeout은 공통 6초로 설정했다. 그 타임아웃이 발생할 때까지 기다린 실험은 아니다.

원본 scenario의 `passed=true`는 시나리오 실행/수집 성공을 뜻한다. 특히 phase-timeout은 결과를 미리 성공으로 단언하지 않고 관측하는 케이스다. 저장 성공 3건이라는 뜻으로 읽으면 안 된다. [사후 관측값 검증](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/analysis.json)은 실제 1/0/0과 신호·로그·정리를 대조한다.

## 실행한 앱과 절차

- Java 21.0.11 / Boot 3.4.3 / Spring 6.2.3 / MySQL 8.4.9. 가상 스레드 비활성화.
- 현재 dirty working tree를 빌드했다. 깨끗한 릴리스 커밋 또는 배포된 운영 앱으로 표현하지 않는다. 실제 계정 설정이 포함되지 않도록 모든 application 설정 파일을 JAR에서 제외하고, 더미 자격증명과 격리 설정을 주입했다.
- 기존 로컬 이미지의 정확한 image ID를 JVM 실행 기반으로 사용하되, 그 이미지의 과거 앱 대신 새 JAR을 read-only 마운트했다. 컨테이너 내부 JAR SHA-256을 빌드 산출물과 대조했다. JAR 해시: `785e080d0cdeb75359fc85d1a71f819cc8be8e4ec894625717e025a8789ae486`.
- 케이스마다 새로운 internal Docker network, tmpfs MySQL, 앱, curl probe를 만들었다. 호스트 게시 포트와 외부 서비스 연결이 없다. readiness는 시작 여부만 확인한다. DB 준비 완료는 이후 seed와 SQL 실행으로 별도 확인한다.
- 게시글 42001의 초기 `view_count=0`을 넣고, 별도 MySQL 연결에서 `SELECT ... FOR UPDATE`로 잠갔다. `/boards/jdbc-check/42001`을 **한 번만** 조회하고 HTTP 200과 본문 표식을 확인했다.
- 30초 주기의 기존 @Scheduled 작업을 기다렸다. `performance_schema.data_lock_waits` 등에서 해당 행에 대한 아래 SQL 대기를 관측하고 DB 값 0을 재확인한 후 종료했다.

```sql
update post p1_0 set view_count=(p1_0.view_count+1) where p1_0.id=42001
```

잠금 연결 ID와 대기 SQL은 기록했지만 blocking thread ID를 그 연결 ID에 직접 JOIN해 단언하지는 않았다. 격리 DB의 단일 대상 행 실험이라는 범위에서 해석한다. HTTP 검사는 상세 페이지를 반복 조회하지 않는다. 반복하면 조회수 입력 자체가 달라지기 때문이다.

종료 대상은 생성 시 기록한 정확한 컨테이너 ID다. entrypoint는 shell을 거치지 않는 `java`이며 PID 1 로그도 남겼다. [Docker stop 문서](https://docs.docker.com/reference/cli/docker/container/stop/)의 설명만으로 신호를 추정하지 않고 실제 Docker events의 signal 15, 강제 종료 케이스의 signal 9와 die 137, `OOMKilled=false`를 대조했다. 잠금 조기 해제 시간은 SIGTERM 수신이 아니라 **stop CLI 호출 시점 기준**이다.

## 로그와 코드의 대조

정상 완료 케이스에서는 `MessageBroker-1` 스레드의 `viewCount flush: updated 1 posts`가 종료 훅보다 먼저 나왔다. 그 뒤 `Flushing pending view counts before shutdown...`, JPA 종료, Hikari 종료가 이어졌다. 최종 DB 조회수는 1이다.

phase-timeout에서는 다음 순서가 남았다.

1. Tomcat의 `Graceful shutdown complete`.
2. `Shutdown phase 0 ends with 1 bean still running after timeout of 1000ms: [messageBrokerTaskScheduler]`.
3. ViewCountService 종료 훅 시작 로그.
4. JPA와 Hikari 종료 로그.
5. SIGKILL 없이 exit 143. 잠금 해제 후 조회수 0.

현재 [ViewCountService](../../src/main/java/com/example/popping/service/ViewCountService.java)는 pending map에서 delta를 `remove`해 배치로 넘긴 뒤 DB를 갱신한다. 따라서 작업 중 delta는 map에 없고, 종료 훅에서 map만 다시 flush해도 이미 진행 중인 배치를 대신 저장할 수 없다. **코드에 근거한 설명**이며, 종료 시점의 map을 실제 JVM에서 덤프한 것은 아니다. 이 실행에서 실패 예외·pending 복원 로그도 확보하지 못했다. “실제 JDBC 실패가 catch되어 복원됐다”거나 정확한 드라이버 취소 경로까지 검증했다고 쓰지 않는다.

마지막 케이스는 Docker SIGKILL을 확인했다. 종료 훅 시작 로그는 수집된 로그에 없고, DB 값은 0이다. 로그 부재만으로 훅이 절대로 실행되지 않았다는 일반적 결론은 내리지 않는다.

이전 [Spring lifecycle 테스트](view-count-shutdown-20260923.md)는 Boot 자동 구성 스케줄러와 mock 저장소를 사용했다. 이번 전체 앱에서는 `MessageBroker-1` 실행 스레드와 `messageBrokerTaskScheduler` timeout이 관측됐다. 두 구성이 동일하다고 간주하지 않는다. 둘 다 확인된 공통 경계는 “기한 안의 진행 작업 완료”와 “기한 이후 데이터 저장 미보장”이다.

## 선택과 다음 범위

| 대안 | 이점 | 비용·남는 문제 | 판단 |
|---|---|---|---|
| 조회수는 근사값으로 두고 종료 예산·관측 보강 | 현재 배치 장점 유지, 새 저장소 없음 | 강제 종료·장기 DB 장애에서 미저장 가능 | 현재 우선 제안 |
| flush에 synchronized 추가 | 동시 실행 경로를 직렬화 | SIGKILL 내구성 없음. 종료 훅까지 막힐 수 있음 | 이번 결과만으로 도입하지 않음 |
| 모든 조회마다 동기 DB 쓰기 | 커밋 성공 확인 경로 단순화 | 조회 경로 지연·DB 부하·핫 행 경합 | 정확도가 요구될 때 비용 비교 |
| Redis/Outbox 등에 영속 인계 | 프로세스 밖에서 재시도 기반 확보 | 원자성·중복·장애 복구·운영 책임 추가 | 손실 허용 정책 확정 전 보류 |

조회수는 결제·정산 데이터가 아니다. 현재 사용처와 제품 요구를 먼저 확인하고, 근사 집계 정책이 맞으면 이를 명시하는 쪽을 추천한다. 단, 이번 1건 실험으로 “30초치만 잃는다” 같은 유실 상한은 제시할 수 없다. DB 장애가 이어지면 미저장 구간은 더 길어진다.

다음 구현 후보는 기존 배치를 유지한 상태에서 **pending 합계, 진행 중 배치, flush 성공/실패·소요 시간 관측**을 보강하는 것이다. map이 비었음을 저장 완료로 오인하지 않게 하는 것이 목적이다. 게이지 snapshot 자체도 종료 허가의 원자적 조건으로 사용하지 않는다. 이후 지연·실패를 넣어 관측이 맞는지 검증한 뒤 종료 예산을 비교한다. 이번에는 이 구현이나 운영 배포를 시작하지 않았다.

## 근거, 보존, 재현 범위

- [실행 도구](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/rig.py), [campaign](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/campaign.json), [artifact](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/artifact.json), [분석 스크립트](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/analyze.py).
- [정상 완료 원본](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/release-in-time/scenario.json), [Spring 기한 초과 원본](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/phase-timeout/scenario.json), [Docker 기한 초과 원본](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v2/docker-deadline/scenario.json). 각 폴더의 app.log, primary.log, cleanup.json과 함께 읽는다.
- v1은 내부망의 호스트 게시 포트 접근 문제로 readiness 단계에서 중단됐다. 상세 GET·종료 실험 이전 실패이며 [기록](../../_workspace/deploy-safety/2026-09-23/jdbc-shutdown-v1/campaign.json)을 보존했다. v2에서 내부 curl probe로 수정했다. JAR은 v1 prepare.py로 성공한 동일 산출물을 재사용했다. v2 prepare.py는 새 버전 출력 경로를 따르도록 수정했으나 이번에 재실행하지 않았다.
- v2 격리 컨테이너는 케이스별 3개씩 총 9개를 소유 라벨·ID 검사 후 정리했고, 원래 실행 중인 10개 ID가 유지됐다. 원래 앱·CI·운영 설정에 이번 변경을 적용하지 않았다.
- 재현 시 같은 날짜 부모 아래 새 버전 폴더에 rig.py와 검증된 app.jar/artifact.json을 복사한 뒤 `python <새 폴더>/rig.py --case all`을 실행한다. 결과가 있으면 실행을 거절한다. scripts/deployment/verified_fixture.py와 이전 gate-v1/isolated.properties 의존성이 있으므로 standalone 도구가 아니다. 실제 실행한 v2 소스 해시는 analysis.json에 남겼다.
- 앱 실행 기반은 image ID로 고정했으나 MySQL/curl 태그는 이번 harness에서 digest pin하지 않았다. 관측 MySQL 버전은 8.4.9이며, 미래 태그 재실행이 완전히 같은 환경이라고 보장하지 않는다.

이번은 실제 앱/JDBC 종료 실험 3건이다. 앞선 전체 회귀 182건을 이번에 다시 실행한 것으로 표기하지 않는다. 현재 앱 비즈니스 코드는 이번 실험 중 변경하지 않았다.
