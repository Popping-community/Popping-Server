# 댓글 데드락 수정 후 Replica 도입 재검증 계획

> 최종 정리(2026-09-10): 댓글 데드락 수정 후 500 VUser를 재검증했고 A·B 각 2회가 유효했다. 5A 본 측정은 실행 중단으로 제외했으며 6B는 미실행이다. B 종료 lag 460·466초로 안정성 미달, 확정 개선율 없음. 복구·독립 검산을 마쳤고 사용자 결정으로 추가 실험 없이 종료한다. [최종 결과와 실제 Grafana 캡처](replica-adoption-fixed-20260910.md). 아래는 해당 시점의 계획·이력이다.

사용자 요청: “실행하고 데드락은 기록해두자.” 2026-09-09 저녁에 새 캠페인 `replica-20260909-adoption-fixed-01`을 실행한다. [데드락 근거·수정·169개 테스트](comment-deadlock-20260909.md)는 별도 기록으로 보존한다.

## 비교와 판정

[이전 도입 계획](replica-adoption-20260909-plan.md)의 부하·자원·반복·채택 기준을 유지한다. 아래의 새 이미지·오류 증거 수집·예약 작업 회피 절차가 이번 계획의 변경점이며, 해당 부분에서는 이 계획이 우선한다.

- **A**: 단일 Primary 1 CPU / 1 GiB, Replica 컨테이너 OFF. 두 풀 모두 Primary. A의 lag는 N/A.
- **B**: Primary·Replica 각각 1 CPU / 1 GiB. Read pool은 Replica, Write는 Primary. Sticky Primary 적용 읽기는 Primary를 선택할 수 있다.
- App 2대, 각각 2 CPU / 2 GiB, 실제 최대 힙 240 MiB. 풀 Write 20 / Read 30, Sticky ON 3초, Redis 세션 OFF. Worker 2개, 내구성 1/1, commit order 유지.
- **500 VUser**, 그룹 300/150/5/5/40, ramp 60초. **ABBAAB, 각 3회**. 슬롯별 smoke → 워밍업 600초 1회 → GTID·유휴 안정화 → 본 측정 900초 1회. 자동 재시도 없음.
- 워밍업 오류·JIT 실패, 본 측정 오류·coverage 실패·입력 변경, 격리·안전 실패는 중단·원복한다. 본 측정에서 JIT/TPS만 불안정하면 제외 이유와 원시 결과를 남기고 예정 슬롯을 계속한다.
- B의 lag UNSTABLE은 재시도나 HTTP 성능 제외 사유가 아니다. 마지막 5분의 기존 안정 기준을 별도로 적용한다.
- HTTP tail `[780,900)`, JIT 2% 기준, TPS 기존 STEADY 기준 유지. 유효 각 3회 미달은 확정 비교 미달로 보고한다.
- DB 총자원 1→2와 복제·라우팅이 함께 추가되는 구성 전체 비교다. 과거 실패 캠페인에 5A·6B를 채워 넣거나 이전 수치와 합쳐 반복수를 만들지 않는다. 데이터는 누적하며 삭제·초기화하지 않는다.

## 새 실행물과 입력 고정

- 이미지: `sha256:cb215e007e159b821a47a46d39214e721926288ed8828147444b08d2f8d769e9`
- 로컬 태그: `popping-replica:20260909-deadlock-fixed-75ae84d62f47`
- 소스 fingerprint: `75ae84d62f47` 접두어. 전체값·파일별 hash·JVM 설정은 새 build manifest에 기록한다.
- 빌드 증거: `.tmp/replica-adoption-fixed/build-output/20260909T093857.471779Z-offline-d049a65b/build.json`. 소스 불변·기존 이미지 태그 보존 검증 통과. 최초 시도는 새 태그를 초기화 스크립트가 거부해 종료됐고, 검증식을 수정한 다음 새 증거 경로로 빌드했다.
- 새 하네스 `.tmp/replica-adoption-fixed/`. 기존 `.tmp/replica-adoption/`와 이전 결과는 수정하지 않았다. 원본 hash를 별도로 검사한다.
- JMX 세 파일은 검증된 원본을 복사해 오류 전용 listener만 추가했다. 이 listener를 제거한 XML 트리가 원본과 동일함을 확인했다. CSV fixture·스레드·타이머·요청·기존 assertion·원래 CSV 집계는 같다.

## 오류 증거 보존

- 모든 pass에서 앱 두 대의 `docker logs --follow` stdout/stderr를 파일로 보존하고, 종료 후 같은 구간을 다시 snapshot한다.
- 각 태스크 컨테이너를 stop한 뒤 rm하기 전에 최종 stdout/stderr를 추가 저장한다. ID·소유 라벨을 확인하며, 수집 실패를 명시적으로 기록하고 원래 서비스 복구는 계속한다.
- JMeter는 기존 CSV와 별도로 **실패 샘플만 XML**에 저장한다. 실패 응답 본문은 남기고 요청 본문·요청/응답 헤더는 저장하지 않는다. CSV에서는 response data 저장이 지원되지 않아 별도 XML을 사용한다. [JMeter 설정](https://jmeter.apache.org/usermanual/properties_reference.html).
- 오류 시 `SHOW ENGINE INNODB STATUS`와 InnoDB deadlock/timeout 카운터를 수집한다. 시작 전 엔진 기록도 저장해 오래된 데드락을 이번 실행의 새 오류로 오인하지 않는다.
- 민감할 수 있는 원본 로그는 로컬 근거 디렉터리에 보존하고, 블로그에는 필요한 SQL 형태·잠금·시각만 발췌한다.
- 하네스 모의 테스트 **42개 통과**. 별도 localhost 합성 응답 200/500 두 건으로 XML에 500 본문만 기록되고 성공 본문·응답 쿠키가 제외됨을 확인했다. 합성 검증은 실제 앱에 요청하지 않았다.

## 예약 작업 회피와 복구

이전 200분 cron guard는 앱이 없는 슬롯 전후의 GTID 대기도 포함한 위치에서 실행됐다. 이번에는 **슬롯 전 GTID·idle을 마친 뒤 앱 생성 직전 120분 guard**를 적용한다. 여유가 부족하면 태스크 앱이 없는 상태로 00:00/04:00 KST 경계를 넘긴 뒤 시작한다.

추가로 활성 앱의 매 격리 검사에서 예약 시각까지 **10분 이하**이면 중단·로그 보존·원복한다. 긴 대기로 시간 여유를 소진해도 예약 작업이 본 측정에 섞이지 않도록 한 안전 중단 기준이다. 예약 작업을 비활성화하거나 생산 코드의 스케줄을 변경하지 않는다. 야간 guard 대기 때문에 전체 완료 시각은 단순히 150분 부하 시간을 더한 시각보다 늦을 수 있다.

물리 메모리 초기 4 GiB, 실행 중 1 GiB / 커밋 여유 2 GiB 기준과 GTID 단계 1,800초·idle 최대 600초는 유지한다. 종료 시 원래 앱·DB·모니터링 상태, 전체 컨테이너 ID·상태·자원·restart policy, 앱 health, 복제/GTID·행 수·입력 hash를 검증한다.

20:01 KST 사전 확인에서는 전체 25개 컨테이너, 물리 여유 약 7.24 GiB·커밋 여유 약 9.87 GiB, 양 DB 1 CPU/1 GiB·복제 정상·GTID 동기화를 확인했다. 실제 run 명령은 시작 직전에 새 snapshot과 journal을 다시 만든다.
