# 배포 종료 점검에서 찾은 조회수 인계 경쟁 — 2026-09-23

## 확인한 결과

배포 검증의 미완료 항목인 메모리 조회수 flush를 읽다가, 정상 실행 중에도 동시 증가분을 잃을 수 있는 구조를 발견했다. 수정 전 8개 writer가 각각20,000번 증가하고 별도 flush를 병행한 실행에서 기대160,000 대비 저장소 mock에 전달된 합은159,025였다. 마지막 onShutdown 호출 후에도975가 부족했다. 이는 **그 실행에서 재현한 메모리 경쟁**이며 운영 유실량이나 유실률이 아니다. 스레드 스케줄에 따라 구버전 결과는 달라질 수 있다.

[수정 전 소스](../../_workspace/deploy-safety/2026-09-23/view-count-v1/ViewCountService.before.java), [실패 XML](../../_workspace/deploy-safety/2026-09-23/view-count-v1/before-tests.xml), [실패 로그](../../_workspace/deploy-safety/2026-09-23/view-count-v1/before-test.log)를 보존했다. 최초 실행 환경의 Java11과 Gradle 캐시 권한 문제를 해소해 Java21에서 실행한 결과다.

## 원인과 선택

기존 `computeIfAbsent(...).increment()`는 mutable LongAdder 참조를 map 연산 밖으로 꺼낸다. flush의 replace→old.sum 사이에 증가하면 포함될 수 있지만 sum 이후 이전 참조에 증가하면 batch에도 map에도 남지 않는다. 또 `remove(postId, fresh)`는 참조 값이 같은지 비교할 뿐 fresh.sum이0인지 확인하지 않아 새 카운터의 증가분까지 제거할 수 있다. ConcurrentHashMap 개별 연산의 안전성이 이 전체 절차를 안전하게 만들지는 않는다.

| 대안 | 이점 | 비용·판단 |
|---|---|---|
| flush만 synchronized | flush끼리 겹침 방지 | increaseView가 같은 경계 밖이면 두 유실 경로를 해결하지 못함 |
| 모든 증가와 flush를 하나의 lock으로 감쌈 | 단순한 직렬화 | DB I/O 동안 전체 조회가 대기할 수 있어 제외 |
| LongAdder의 모든 접근을 compute 안에 제한 | 키별 원자 인계 가능 | 키별 직렬화를 하면서 mutable 상태·참조 규칙까지 관리해야 함 |
| **불변 Long + merge/remove** | 키별 원자 증가·인계, 간단한 실패 복원 | boxing과 동일 키 경합 비용을 감수. 처리량 개선 주장은 없음 |
| Redis나 이벤트 저장소로 이동 | 프로세스 밖 내구성 설계 가능 | 지금 재현한 메모리 경쟁에 비해 운영·복구 범위가 커 보류 |

채택한 [ViewCountService](../../src/main/java/com/example/popping/service/ViewCountService.java)는 증가 시 merge, 인계 시 remove, DB 예외 시 merge로 복원한다. 인계한 값은 batch만 소유하고 이후 증가는 map에 남는다. DB 작업은 map 연산 밖에서 수행한다. Java21 [ConcurrentHashMap 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)의 키별 연산 계약을 사용하며 전체 map의 원자 snapshot을 주장하지 않는다.

## 검증 범위

기존11건에 새4건을 추가해 조회수 클래스15건, 관련 게시글·캐시를 합쳐54건이 통과했다. [대상 테스트 XML](../../_workspace/deploy-safety/2026-09-23/view-count-v1/targeted-tests/TEST-com.example.popping.service.ViewCountServiceTest.xml), [대상 실행 로그](../../_workspace/deploy-safety/2026-09-23/view-count-v1/after-test.log).

- 동시 증가·flush 이후 mock 반영 합160,000 및 pending0. Future.get으로 worker 실패를 전달하고 대기에 시간제한을 둔다.
- 첫 DB 쓰기를 latch로 정지한 동안 새 증가를 두 번째 flush가 가져가도록 제어: delta2와1을 각각 한 번, 최종 합3.
- DB 반영 전 예외를 주입하면서 새 증가1을 받음: 실패분2와 합쳐 pending3, 다음 flush에서3을 전달. 실제 DB rollback 테스트가 아니다.
- onShutdown 중 DB 반영 전 예외: pending1이 메모리에 남는다. 이를 내구성 보장 성공으로 읽지 않는다.

전체 회귀 실행은 별도 loopback MySQL·Redis와 portfolio_regression DB에서 **179건, 실패·오류·skipped0건**으로 통과했다. 생성한2개 컨테이너를 정리하고 기존10개 ID를 보존했다. 정확한 실행·정리 결과는 [전체 실행 결과](../../_workspace/deploy-safety/2026-09-23/view-count-v1/full-suite-result.json)에, 수치와 소스 해시는 [증거 요약](../../_workspace/deploy-safety/2026-09-23/view-count-v1/evidence.json)에 기록했다. 기존 Popping DB를 사용하지 않았다.

독립 Codex 검토에서 설계 및 최종 두 Java 파일의 차단 문제는 없었다. simplify 검토에서도 기존 자료구조 연산 재사용으로 충분해 추가 추상화를 만들지 않았다. Claude가 이 변경 자체를 다시 검수한 것은 아니다.

## 해결하지 않은 것과 다음 판단

- 메모리 집계이므로 SIGKILL·프로세스 소실 시 pending/in-flight는 잃을 수 있다. DB가 계속 실패한 채 종료해도 복원분이 프로세스 밖으로 옮겨지지 않는다.
- 커밋 성공 여부가 불명확한 예외 후 복원·재시도는 중복을 만들 수 있다. exactly-once, durable retry, 모든 DB 장애 복구를 증명하지 않는다.
- onShutdown은 다른 진행 중 flush 완료를 기다리거나 이후 증가를 차단하는 lifecycle barrier가 아니다. `scur=0`이 이 barrier를 대신하지 못한다.
- getPendingCount는 DB로 인계 중인 batch를 포함하지 않는다. 분산 인스턴스의 정확한 실시간 조회수 표시는 별도 문제다.
- 기존 TransactionTemplate은 기본 전파 설정이다. CLAUDE.md의 과거 REQUIRES_NEW 설명을 최신 사실로 쓰지 않는다.
- 운영 배포, 실제 SIGTERM 중 DB 장애 실험, 새 핫키 처리량 측정은 하지 않았다. 새 이미지 배포 성공이나 기존 배포 실험 이미지에 이번 수정이 들어갔다고 주장하지 않는다.

후속 [Spring 종료 순서 검증](view-count-shutdown-20260923.md)에서 Boot 스케줄러의 기한 내 대기와 기한 초과 시 종료 진행을 확인했다. 서비스에 새 잠금을 추가하지 않았다. 실제 JDBC/SIGTERM 경로와 손실 허용 정책은 남아 있다. 장애에도 한 건도 잃지 않아야 한다는 요구가 정해지기 전에는 Redis/Outbox를 추가하지 않는다.
