# 조회수의 pending과 진행 중 배치를 구분하는 지표

## 변경 이유와 선택

[실제 JDBC 종료 실험](view-count-jdbc-shutdown-20260923.md)에서는 HTTP 종료 완료와 exit143만으로 조회수 저장 완료를 알 수 없었다. 현재 알고리즘은 map에서 증가분을 꺼낸 뒤 DB에 쓰므로, map이 비었어도 진행 중인 배치가 남을 수 있다.

[ViewCountService](../../src/main/java/com/example/popping/service/ViewCountService.java)에 기존 Micrometer만 사용해 관측을 추가했다. 새 의존성·저장소·스케줄러·종료 잠금은 없다. DB 갱신, 실패분 복원, 캐시 제거와 30초 flush 정책을 유지한다.

별도 지표 서비스를 만들기보다 집계 상태를 소유한 서비스에서 작은 고정 지표를 등록했다. pending은 scrape 시 map 값을 합산해 읽으므로 비용은 pending 게시글 수에 비례한다. 요청마다 별도의 전역 카운터를 갱신하거나 잠금을 추가하지 않는 쪽을 선택했다. 이 선택의 부하 영향을 성능 실험으로 측정하지는 않았다. in-flight는 여러 flush가 겹칠 수 있어 원자적 가산·차감으로 관리한다.

## 지표 계약

| Prometheus 지표 | 단위·의미 | 주의할 해석 |
|---|---|---|
| `popping_view_count_pending` | 아직 map에 있는 조회수 증가분 합계 | 게시글 수가 아님. weakly consistent 순회 |
| `popping_view_count_in_flight` | 활성 배치가 소유한 증가분 합계 | 현재 JDBC 실행 항목뿐 아니라 같은 배치에서 순서를 기다리는 항목도 포함 |
| `popping_view_count_write_seconds_count{result="returned"}` | TransactionTemplate 호출이 예외 없이 반환한 항목별 시도 횟수 | 조회수 건수·affected rows·영속 저장 보장과 다름 |
| `popping_view_count_write_seconds_count{result="error"}` | 예외 또는 Error로 반환하지 못한 항목별 시도 횟수 | 재시도는 새로운 시도. 유실 건수로 계산하지 않음 |
| `popping_view_count_write_seconds_sum{result=...}` | 완료된 시도의 누적 소요 시간(초) | catch한 Exception 경로에서는 실패 로그·메모리 복원까지 포함. Error 전파 경로는 그 복원 없이 종료. 캐시 제거 제외. 아직 끝나지 않은 호출은 미포함 |

Timer의 max는 영구적인 역대 최고값이나 대시보드에서 선택한 시간 범위의 최대값이 아니라 time-window max다. [공식 Timer 설명](https://docs.micrometer.io/micrometer/reference/concepts/timers.html)은 `expiry × bufferLength`를 만료 범위로 설명하며 기본 bufferLength는 3이다. Prometheus의 expiry 기본은 registry step에서 가져온다([1.14.4 소스](https://github.com/micrometer-metrics/micrometer/blob/v1.14.4/implementations/micrometer-registry-prometheus/src/main/java/io/micrometer/prometheusmetrics/PrometheusMeterRegistry.java)). 따라서 모든 환경에 “기본 2분”을 적용하지 않으며 새 표본 없이 시간이 지나면 0으로 바뀔 수 있다. 결과 태그는 `returned/error` 두 개로 고정한다. 게시글 ID·사용자 ID·예외 메시지를 태그로 넣지 않는다. 지표는 프로세스별이며 재시작 후 누적값이 초기화된다.

[Micrometer Gauge 문서](https://docs.micrometer.io/micrometer/reference/concepts/gauges.html)에 따라 gauge는 관측 순간의 값이다. scrape 사이에 일어난 변화를 모두 남기지 않는다. [Timer 문서](https://docs.micrometer.io/micrometer/reference/concepts/timers.html)에 따라 횟수와 소요 시간을 같은 Timer로 수집하며 별도 성공/실패 Counter를 중복 등록하지 않았다.

map → 배치 인계와 지표 갱신, 실패 복원과 in-flight 차감은 하나의 원자적 연산이 아니다. **pending + in-flight도 정확한 미저장 총량이나 종료 허가 조건이 아니다.** 인계 중 잠깐 빠지거나 복원 중 잠깐 겹칠 수 있다. fatal Error 후 소유권 수치를 정리해도 데이터가 복구된 것은 아니다. 종료 직전 마지막 scrape 누락과 커밋 결과 불명도 이 지표로 해결되지 않는다.

DB 메서드는 affected row 수를 반환하지 않고, TransactionTemplate은 호출 문맥에 따라 외부 트랜잭션에 참여할 수도 있다. 그러므로 `returned`를 “영구 저장 성공”으로 바꾸지 않았다. 현재 scheduled 경로에서의 관측과 임의 트랜잭션 문맥의 보장은 분리한다.

## 조회 예시

앱별 현재 상태는 두 gauge를 나란히 본다. 잠금 대기 때 pending=0이면서 in-flight>0이면 map 밖에 작업이 남아 있다. 두 값이 0이라는 이유만으로 종료하지 않는다.

```promql
popping_view_count_pending
popping_view_count_in_flight

# 완료한 시도 중 오류 비율; 조회수 유실률이 아님. 시도가 없으면 NaN 가능.
sum(rate(popping_view_count_write_seconds_count{result="error"}[5m]))
/
sum(rate(popping_view_count_write_seconds_count[5m]))

# 완료된 시도의 평균 시간. 아직 멈춰 있는 호출은 제외된다.
sum by (result) (rate(popping_view_count_write_seconds_sum[5m]))
/
sum by (result) (rate(popping_view_count_write_seconds_count[5m]))
```

운영 임계값과 알림은 아직 정하지 않았다. 프로세스별 `up`, 재시작과 scrape 누락을 함께 봐야 한다. 위 쿼리는 사용 예시이며 실제 Grafana 패널에 반영하거나 Prometheus 서버에서 실행한 결과는 아니다.

## 검증 구성

[ViewCountMetricsTest](../../src/test/java/com/example/popping/service/ViewCountMetricsTest.java)는 완료 횟수를 조회수 건수와 구분하고 다음 경계를 검사한다.

- 빈 flush는 시도를 만들지 않음.
- 첫 UPDATE가 멈췄을 때 같은 배치의 다음 항목까지 in-flight에 포함. 새 조회는 pending에 남음.
- 트랜잭션 완료 단계의 모의 예외는 error로 집계. 새 조회와 복원분을 합쳐 재시도하며 별도 시도로 계산.
- 겹친 flush 하나가 끝나도 다른 배치의 in-flight를 지우지 않음.
- 두 항목 배치의 첫 호출에서 fatal Error가 나면 전파를 유지하고 계측상 소유권만 정리. 데이터 복원 보장은 없음.
- 실제 PrometheusMeterRegistry의 scrape 텍스트에서 이름·고정 태그·단위를 확인.

모의 시계의 250/400/100ms는 단언용 값이며 실제 지연 측정치가 아니다. 최초 컴파일은 레지스트리를 AutoCloseable로 잘못 가정한 테스트 때문에 실패했다. 명시적 finally/close로 수정했으며 앱 장애 실험으로 계산하지 않는다.

실제 앱 검증은 별도의 내부망 앱·MySQL·curl에서 실행한다. 원래 컨테이너는 건드리지 않고, 현재 소스의 설정 제외 JAR을 read-only 마운트해 런타임 해시를 확인한다. 이 환경에만 `/actuator/prometheus` 노출을 추가한다. 제품의 외부 접근 설정은 변경하지 않는다. 결과는 아래 후속 검증 기록을 기준으로 읽는다.

## 2026-09-23 실제 검증 결과

| 단계 | pending | in-flight | returned 시도 누계 | error 시도 누계 | DB 조회수 |
|---|---:|---:|---:|---:|---:|
| 시작 | 0 | 0 | 0 | 0 | 0 |
| 첫 GET 후 실제 UPDATE 잠금 대기 | 0 | 1 | 0 | 0 | 0 |
| 잠금 해제 후 저장 | 0 | 0 | 1 | 0 | 1 |
| 두 번째 GET 후 UPDATE 잠금 대기 | 0 | 1 | 1 | 0 | 1 |
| MySQL lock timeout 후 복원 | 1 | 0 | 1 | 1 | 1 |
| 잠금 해제·다음 scheduled 재시도 후 | 0 | 0 | 2 | 1 | 2 |

상세 GET은 총 2회이며, 잠금 대기 SQL을 두 번 관측했다. 각 단계의 마지막 안정된 scrape와 별도 SQL 조회를 대조했다. 동시에 읽은 원자적 스냅샷은 아니다. 실패 시도의 Timer 합계는 6.03250509초이며 앱 로그에 실제 `Lock wait timeout exceeded`와 조회수 flush 실패 예외가 남았다. MySQL 잠금 기한은 실험용 6초다. 이것은 한 번의 로컬 실패·복구 관측이고 운영 SLO나 일반적인 지연 수치가 아니다.

이 실험은 JVM을 유지해 실패분 복원과 다음 주기 재시도를 관찰했다. 종료·크래시 내구성 실험이나 앞선 종료 유실 문제의 해결로 표현하지 않는다. gauge에서 pending 복원을 보았고, 최종 DB2를 별도로 조회해 이번 입력 2건의 반영을 확인했다. MySQL/curl 이미지 태그를 digest 고정하지 않은 재현 한계와 blocker connection 직접 JOIN 미검증 한계는 이전 harness와 같다.

최초 대상 테스트 23건(기존18+신규5) 통과 후, 독립 Codex 검토에서 제안한 fatal Error 정리 테스트를 추가했다. 최종 전체 회귀는 **188건, 실패0·오류0·skipped0**이다. 새 지표 테스트 6건도 이 실행에 포함된다. 검토에서 구현 차단 문제는 없었으며, simplify 검토에서는 별도 추상화나 추가 잠금 없이 현재 구조를 유지했다. 최초 구현 완료 시점에는 Claude 검수를 수행하지 않았으며, 이후 검수는 아래 절에 구분했다.

실제 scrape용 컨테이너 3개와 전체 회귀용 MySQL·Redis 2개를 각각 정확한 소유 ID·라벨을 확인해 정리했다. 두 실행 모두 원래 10개 컨테이너 ID를 보존했다. 프로덕션 앱 코드에는 계측을 추가했지만 원래 실행 중인 앱에 배포하거나 CI를 변경하지 않았다.

- [수치·해시·회귀 요약](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/evidence.json), [사후 검증 코드](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/analyze.py)
- [실제 관측 JSON](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/metrics/scenario.json), [전체 앱 로그](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/metrics/app.log)
- [대기 시 원본 scrape](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/metrics/blocked-success.prom), [실패 복원 scrape](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/metrics/restored-after-error.prom), [재시도 후 scrape](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/metrics/retry-saved.prom)
- [전체 테스트 보고서](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/full-report/index.html), [전체 실행·정리 결과](../../_workspace/deploy-safety/2026-09-23/view-metrics-v1/full-suite-result.json)

후속 우선순위는 배포 종료 예산과 조회수 손실 허용 정책을 이 근거로 정리하는 것이다. 단순 지표 수집만으로 운영 알림·종료 게이트·무손실 보장을 완성했다고 주장하지 않는다.

## 후속 Claude 검수

실제 Claude Opus 5 high에 코드·테스트·실험·블로그 15개 파일의 번호와 해시가 있는 스냅샷을 전달했다. 도구 실행은 비활성화했다. 원문 판정은 “조건부 진행(사실상 진행 가능)”이고 새 계측 회귀나 필수 코드 수정은 발견하지 못했다. [검수 원문](../../_workspace/deploy-safety/2026-09-23/claude-metrics-review-v1/claude-review.md)과 [대조·채택 기록](../../_workspace/deploy-safety/2026-09-23/claude-metrics-review-v1/decision.md)을 보존했다.

이번에 Exception/Error 시간 범위와 max 설명을 보완했다. 실제 앱은 항목 1개씩의 배치였고 다항목·중첩 flush·fatal Error는 mock 테스트 근거다. 상세 GET 직후 첫 pending=1 구간도 실제 scrape로 잡지 않았으며 실제 pending>0 관측은 타임아웃 복원 후다. Claude는 제품 설정 파일을 받지 않았으므로 제품 설정 변경 여부까지 검증했다고 표현하지 않는다.

188건 XML과 evidence의 소스 해시를 [다시 대조](../../_workspace/deploy-safety/2026-09-23/claude-metrics-review-v1/evidence-recheck.json)했다. 현재 소스가 당시 사후 기록 해시와 일치하며 테스트 합계도 동일하다. 다만 full_suite.py는 테스트 직전·직후 소스/클래스 해시를 저장하지 않았다. 사후 해시와 원본 XML의 대응 기록을 실행 전후의 원자적 출처 증명으로 격상하지 않는다. 코드 변경이 없어 이번 검수에서 테스트를 다시 돌리지는 않았다.
