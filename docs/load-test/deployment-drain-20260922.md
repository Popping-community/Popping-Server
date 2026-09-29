# 기존 요청을 끝낸 뒤 앱을 종료하는 로컬 검증

검증일: 2026-09-22. 후보 투입 게이트에 이어 **기존 앱 신규 유입 차단 → 관측된 요청 완료 → SIGTERM 종료**를 구현하고 격리 환경에서 확인했다. 운영 CI에 연결하거나 원격 배포한 결과는 아니다.

## 문제와 선택

readiness가 통과한 새 앱을 투입해도 기존 앱을 곧바로 종료하면 처리 중인 쓰기가 끊길 수 있다. 고정 시간 sleep은 요청이 끝났다는 근거가 없으므로 쓰지 않았다. HAProxy `drain`은 persistence 요청을 허용하는 경우가 있어, 이번 예외 없는 HTTP 구성에서는 `MAINT`로 신규 배정을 막았다. 별도의 session 강제 종료 명령과 `on-marked-down shutdown-sessions`는 사용하지 않았다.

종료 도구는 MAINT 유지, 누적 세션 `stot` 불변, `scur=0/qcur=0` 3회 연속, 후보 UP와 직전 L7 성공을 요구한다. 종료 직전에 이를 재확인하고 정확한 컨테이너 ID·Compose project/service label·StartedAt·프록시 대상 IP를 대조한다. 컨테이너 이름만으로 종료하지 않는다. SIGTERM만 전송하고 종료가 늦어져도 SIGKILL로 바꾸지 않는다.

관리 인터페이스 실패나 후보 이상이면 중단한다. 타임아웃이면 기존 앱을 MAINT·running 상태로 남긴다. 자동 복귀는 배제했다. 결과가 불명확한 상태에서 트래픽을 되돌리거나 재종료하지 않고 운영자가 확인할 수 있게 하는 선택이다.

## 재현 방법

- 별도 Docker Compose `popping-drain-check-20260922`: MySQL, Redis, 앱 두 대, HAProxy 2.8. 모든 공개 포트는 loopback이다.
- 두 앱은 동일한 실제 Popping JAR: SHA-256 `d1decd9949ffc489017e4df91ef3fc1fb71685370c5bf136400035e6a56f0d2c`. 버전 간 호환성 실험이 아니다.
- 기존 앱에서 발급한 Redis SESSION으로 후보의 보호 페이지를 확인하고 후보를 투입했다.
- 테스트용 post 행을 별도 트랜잭션으로 잠근 뒤 실제 댓글 POST를 한 번 보냈다. 해당 행의 `data_lock_waits=1`, 기존 앱 `scur>0`, 클라이언트 POST 미완료를 함께 확인했다.
- 프록시·클라이언트 timeout은 60초. MySQL 전역 lock timeout은 45초지만 기존 풀 연결의 세션 값은 50초일 수 있다. 관측 대기는 이보다 짧았고 실제 DB 대기와 미완료 요청을 직접 확인했다.
- 동일 세션의 보호 GET을 약 5req/s로 예약했다. 부하 용량 또는 최대 처리량 실험은 아니다.

## 결과

| 시나리오 | 진행 요청/종료 판정 | 보호 GET | DB/연결 검증 |
|---|---|---:|---|
| 첫 timeout 시도 | 후보 health 표기 오판으로 control_error, 종료 호출 0. 의도한 timeout 검증은 실패 | 22건 모두 200·본문 확인 | POST 200, 댓글 1회 저장 |
| 수정 후 timeout-v2 | 2초 대기 후 종료 호출 0. 같은 ID/StartedAt으로 running, 잠금 해제 전후 MAINT 유지 | 26건 모두 200·본문 확인 | POST 200, 반환 ID=DB ID, 부모 count=1 |
| complete | POST 완료 후 세션/큐 0을 확인하고 SIGTERM. exited 143, OOM false | 24건 모두 200·본문 확인 | POST 200, 반환 ID=DB ID, 부모 count=1 |

수정 후 두 시나리오 모두 MAINT 확인 뒤 시작한 보호 GET은 전부 후보로 향했다. POST에 사용한 동일 HTTP/1.1 연결에서 후속 보호 GET을 보냈을 때도 로컬 TCP 포트가 유지됐고 후보 응답을 받았다. 자동 POST 재전송은 없었다.

정상 시나리오에서 POST 응답 완료 시각은 `1790078313.0698798`, 종료 callback 시작은 `1790078313.7249355`(호스트 Unix epoch seconds)였다. 실제 SIGTERM은 그 뒤 소유권 재확인을 거쳐 전송했다. 컨테이너 종료 시각은 Docker가 별도로 기록하므로 호스트 시계와 정밀 지연 비교하지 않는다. Spring 종료 로그는 `old.log`에 보존했다.

## 첫 실패에서 수정한 것

처음에는 `check_status == L7OK`만 정상으로 인정했다. HAProxy 공식 통계 정의에 따르면 검사 중에는 이전 결과 앞에 `* `가 붙는다. 실제 600회 연속 조회에서 `UP/L7OK` 597회, `UP/* L7OK` 3회를 관측했다. 이 유효한 상태를 실패로 오판하는 버그를 수정했다. 첫 실패 당시 후보 원본 상태는 저장하지 않아 동일 표기였다고 확정할 수는 없다. 현재는 후보 status/check_status도 각 샘플에 남긴다.

`UP`이며 `L7OK` 또는 `* L7OK`일 때만 허용하고 실패·초기화·L4 결과와 DOWN은 계속 거부한다. 검사 중 이전 성공을 읽는 것이므로 후보의 미래 건전성을 보장하지 않는다. 첫 실패 JSON을 덮어쓰지 않고 재실행 이름과 post ID를 구분했다. [HAProxy 2.8 통계 정의](https://docs.haproxy.org/2.8/management.html#9.1)

## 코드와 회귀 검증

- 도구: [quiesce_server.py](../../scripts/deployment/quiesce_server.py), 사용 계약: [README](../../scripts/deployment/README.md).
- 배포 도구 단위 테스트 16개 통과(기존 admission 7개 + quiescence 9개). 바쁜 요청/큐, 후보 실패, 새 유입, 마지막 조회 지연, 종료 결과 불명, 강제 종료 없는 timeout을 포함한다.
- 전체 Java 테스트: 31 suites, 175 tests, 실패·오류·skip 0. 격리 MySQL과 테스트 전용 설정을 사용했다. 이번 단계에서 운영 Java 코드는 변경하지 않았다.
- 독립 리뷰에서 종료 직전 deadline 재검사, Docker 호출 시간 제한, 테스트 실패 시 제어기 회수 후 잠금 해제, timeout 전후 MAINT 검증을 반영했다.
- 런타임 완료 후 fixture의 정리 중 예외에도 결과 저장 finally에 도달하도록 보강했다. 이 마지막 실패 정리 경로는 실제 예외 주입으로 재실행하지 않았으며, 정상/timeout 증거는 그 보강 전 실행 결과다.
- 실험 컨테이너 5개 정리 후 기존 컨테이너 ID가 모두 실행 중임을 확인했다. 임시 SESSION 파일도 삭제했다.

## 한계와 다음 단계

HAProxy `scur`는 앱 내부 작업 전체를 뜻하지 않는다. HTTP timeout 뒤 JDBC 작업, 직접 앱 접근, 비동기 작업, WebSocket, 소비자에는 별도 처리가 필요하다. 이번 주장은 DB 잠금으로 관측한 HTTP 쓰기가 완료된 뒤 종료됐다는 범위다. **요청 진행 중 SIGTERM을 받은 Spring이 완료를 기다리는 동작, 실제 다른 이미지 교체·롤백, 고부하 용량, 호스트 장애 HA는 증명하지 않았다.**

다음은 격리 환경에서 두 개의 식별 가능한 이미지로 후보 실패 시 기존 버전 유지와 정상 교체를 검증하는 것이다. 스키마 변경 없는 경우부터 시작하고, 이미지·상태 불명 시 중단 조건을 먼저 정한다. 운영 CI 연결은 그 결과를 확인한 다음 별도 단계로 다룬다.

## 원본

작업 경로: `_workspace/deploy-safety/2026-09-22/drain-v1/`.

- `plan.md`, `review.md`: 사전 계약과 검토
- `scenario-timeout.json`, `quiesce-timeout.json`: 첫 실패 원본
- `scenario-timeout-v2.json`, `scenario-complete.json`: 성공 시나리오와 보호 GET/DB/연결 증거
- `quiesce-timeout-v2.json`, `quiesce-complete.json`: 제어기 판정
- `full-suite-results.json`, `old.log`, `proxy.log`, `db-final.json`, `cleanup.json`

MAINT·세션 종료·SIGTERM의 의미는 [HAProxy 관리 문서](https://docs.haproxy.org/2.8/management.html#9.3), [HAProxy 설정](https://docs.haproxy.org/2.8/configuration.html#5.2-on-marked-down), [Docker stop의 강제 종료 동작](https://docs.docker.com/reference/cli/docker/container/stop/)과 대조했다.
