# 조회수 flush와 Spring 정상 종료 순서 — 2026-09-23

## 결론

Spring Boot3.4.3 / Framework6.2.3의 자동 구성된 ThreadPoolTaskScheduler가 진행 중인 scheduled flush를 lifecycle 기한 안에서 기다리는 것을 확인했다. 따라서 조회수 서비스에 별도 synchronized/lock을 추가하거나 `await-termination=true`로 바꾸지 않았다. 새로 추가한 것은 [종료 순서 테스트](../../src/test/java/com/example/popping/service/ViewCountShutdownLifecycleTest.java)다. 앱·CI·운영 설정 변경이나 배포는 하지 않았다.

`onShutdown()` 직접 호출만으로는 이 결론을 낼 수 없다. 실제 Spring 컨텍스트 close, Boot의 TaskSchedulingAutoConfiguration과 LifecycleAutoConfiguration, @EnableScheduling, 현재 ViewCountService의 @Scheduled 메서드를 함께 실행했다. 스케줄러 Bean이 ThreadPoolTaskScheduler임을 단언해 비관리 fallback 스케줄러와 구분했다. 가상 스레드는 껐다. 저장소와 TransactionTemplate은 mock이며, 실제 JDBC 트랜잭션·MySQL shutdown 실험은 아니다.

## 세 시나리오

| 조건 | 제어 방법 | 관측 및 완료 조건 |
|---|---|---|
| 정상 저장 | 먼저 쌓인2를 scheduled flush가 인계한 뒤 저장소 호출을 latch로 정지. 새 증가1을 추가하고 컨텍스트 close. lifecycle5초 안에 해제 | 닫기 호출이 기다리고, hook·자원 파괴 전 scheduled 작업이 완료. 종료 훅에서 남은1을 처리하여 mock 저장 합3 / pending0 |
| 반영 전 확정 실패 | 같은 순서로 정지 후 해제 시 첫 mock 쓰기가 반영 전 예외 | 실패분2 복원 후 종료 훅이 새 증가1과 합쳐 재시도. mock 저장 합3 / pending0. 실제 DB rollback 검증은 아님 |
| lifecycle 기한 초과 | 기한200ms, 새 증가 없이 최초2의 쓰기를 계속 정지. interrupt에 즉시 끝나지 않는 드라이버를 mock으로 모델링 | close가 반환하고 test storage가 파괴됐는데 첫 쓰기는 미완료. 해제 후 mock이 쓰기를 거절하고 서비스가 pending2로 복원. 저장0. 이 메모리 값의 내구성은 없음 |

정상/확정 실패의 기록 순서는 `scheduled-write-entered → context-close → scheduled-write-returned → pre-destroy → shutdown-returned → storage-destroyed`다. 실패분 복원과 캐시 처리까지 끝나야 실제 scheduler task가 완료되므로, repository 반환 시점만으로 종료 완료를 추정하지 않았다. closing Future 완료와 최종 합계도 검사했다.

기한 초과에서는 `pre-destroy → shutdown-returned → storage-destroyed`가 첫 쓰기 반환보다 먼저 관측됐다. interrupt의 정확한 위치는 스레드 스케줄에 따라 달라질 수 있어 고정 순서로 단언하지 않는다. 테스트는 barrier 해제 후 scheduler termination까지 기다린 다음 복원분을 확인한다. 파괴된 storage가 쓰기를 거절하는 동작은 **테스트 모형**이며 실제 Hikari/JDBC의 모든 종료 동작을 대표하지 않는다.

5초와200ms는 실험 시간제한이다. 운영 TTL, RTO, Docker 종료 유예의 권장값이 아니다. 150ms 동안 closing Future가 미완료임을 확인하고 latch를 해제했으며, 모든 종료 대기에 상한을 뒀다.

## 왜 새 잠금을 넣지 않았나

공식 [ExecutorConfigurationSupport 6.2.3](https://github.com/spring-projects/spring-framework/blob/v6.2.3/spring-context/src/main/java/org/springframework/scheduling/concurrent/ExecutorConfigurationSupport.java)는 기본 설정에서 early shutdown과 coordinated lifecycle stop을 사용한다. `waitForTasksToCompleteOnShutdown=false`를 보고 바로 작업을 끊는다고 해석하면 이 앞선 단계를 놓친다. 이 파일의 `stop(callback)`은 실행 중 task가 끝난 후 callback을 호출한다.

`waitForTasksToCompleteOnShutdown=true`는 단순히 “기존 대기에 대기 하나 더 추가”하는 옵션이 아니다. 같은 공식 소스에 따르면 coordinated stop 대신 destruction 단계로 경로가 달라질 수 있다. 필요한 효과가 기본 구성에서 확인됐으므로 이번에 바꾸지 않았다.

[ScheduledAnnotationBeanPostProcessor](https://github.com/spring-projects/spring-framework/blob/v6.2.3/spring-context/src/main/java/org/springframework/scheduling/annotation/ScheduledAnnotationBeanPostProcessor.java)는 @Scheduled 등록·취소에 관여한다. 첫 테스트에서는 관측용 subclass가 flush 메서드를 override하여 실제 스케줄 등록을 가렸다. 세 테스트가 진입 barrier에서 실패했다. override를 제거해 실제 inherited 메서드를 실행하도록 고쳤으며 실패 시도의 [소스](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/fixture-attempt1.java), [XML](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/fixture-attempt1.xml), [로그](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/fixture-attempt1.log)를 보존했다. 이를 앱 결함이나 수정 전후 성능으로 계산하지 않는다.

## 근거와 재현

최종 전체 회귀는 **182건, 실패·오류·skipped0건**으로 통과했다. 격리 MySQL·Redis2개를 정리하고 기존10개 컨테이너 ID를 보존했다. ViewCountService 소스 해시는 직전 작업과 같음을 확인했다.

독립 Codex 파일 검토에서 차단 문제는 없었고, indexOf 순서 비교는 사건이 누락됐을 때 -1로 통과할 수 있다는 보강 의견을 받았다. containsSubsequence로 사건 존재와 순서를 함께 단언하도록 수정하고 **최종 대상3건을 다시 통과**했다. 전체182건의 실행 소스는 [별도 snapshot](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/LifecycleTest.full-suite.java)에 보존했으며, 이후 단언 변경과 최종 소스 해시는 [최종 검증 기록](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/final-verification.json)에 구분했다. 블로그 캡처는 실제 전체 회귀 보고서다. 후속 대상 검사를 전체 회귀 재실행으로 표기하지 않는다.

- [대상 테스트 보고서](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/targeted-report/classes/com.example.popping.service.ViewCountShutdownLifecycleTest.html): 최초 fixture 수정 후3건 통과.
- [대상 XML](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/targeted-tests/TEST-com.example.popping.service.ViewCountShutdownLifecycleTest.xml): Boot/Spring 버전·사건 순서·저장 및 pending 합계를 기록.
- [전체 회귀 실행 결과](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/full-suite-result.json), [전체 XML 요약과 소스 해시](../../_workspace/deploy-safety/2026-09-23/view-shutdown-v1/evidence.json). fixture는 @TestConfiguration으로 분리해 다른 SpringBootTest 스캔에 섞이지 않게 했다.
- Java21에서 `gradlew.bat test --tests com.example.popping.service.ViewCountShutdownLifecycleTest`로 DB 없이 재현한다. 전체 회귀는 별도 loopback MySQL·Redis를 사용하는 full_suite.py로 실행하고 정확한 생성 ID만 정리했다.

## 보장하지 않는 것

실제 운영의 스케줄러 타입·가상 스레드·외부 주입 설정이 같다고 확인한 것은 아니다. HTTP 서버를 포함한 전체 앱 SIGTERM, 진행 요청 중 신규 증가 차단, 실제 DB 연결 대기·커밋 결과 불명, SIGKILL 내구성도 별도다. 스케줄러를 거치지 않고 임의 스레드에서 호출한 flush까지 Spring이 기다리는 것은 아니다.

기한을 넘기면 중단된 작업의 메모리 복원이 프로세스 종료 후 보존되지는 않는다. @PreDestroy의 DB 쓰기가 별도로 지연될 수도 있으므로 lifecycle timeout을 전체 프로세스 종료의 엄격한 상한으로 표현하지 않는다. 기존 로컬 컨테이너의 관측 StopTimeout1과 소스 opt-in Spring30초의 관계도 이 테스트로 해결되지 않는다.

후속 [실제 앱/JDBC 종료 실험](view-count-jdbc-shutdown-20260923.md)을 완료했다. 조건별 1회에서 기한 내 잠금 해제는 DB1/exit143, Spring 기한 초과는 DB0/exit143, Docker 기한 초과는 DB0/exit137이었다. 전체 앱에서는 MessageBroker-1 실행 스레드와 messageBrokerTaskScheduler timeout이 관측되어 이 작은 테스트와 구성 차이를 구분했다. 조회수의 손실 허용 정책이 정해지기 전에는 Redis/Outbox나 무제한 종료 대기를 도입하지 않는다.
