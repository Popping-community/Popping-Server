# Replica 도입 비교 재실행과 초기 측정 기록 대조

> 실행 종료: `replica-20260910-adoption-fixed-01`은 5A 워밍업 JIT 기준 초과로 FAILED 종료됐다. 1A·2B·3B·4A 본측정만 완료했고 13:00:18 KST 원복 검증을 마쳤다. [실제 결과·초기 원본 재계산·Grafana](replica-adoption-rerun-20260910.md). 아래는 실행 전 계획이며 사후 변경하지 않는다.

2026-09-10 사용자 요청: “일단 어제 실패했던 replica 도입 부하테스트를 진행해서 새로운 부하테스트 방법을 도입하기 전과 비교해보자.” 새 캠페인 `replica-20260910-adoption-fixed-01`으로 실행한다. 이전 실패 캠페인을 이어 쓰지 않는다.

## 실행 조건

- 동결 하네스 `.tmp/replica-adoption-fixed/`와 어제 데드락 수정 이미지를 그대로 사용한다. 입력·생산 소스는 변경하지 않는다.
- A: Primary 1 CPU / 1 GiB, Replica 컨테이너 OFF. B: Primary·Replica 각각 1 CPU / 1 GiB. 이번 CPU 증설·재배분은 없다.
- App 2대 각각 2 CPU / 2 GiB, 실제 최대 힙 240 MiB, Write 20 / Read 30, Sticky Primary ON 3초, Redis 세션 OFF. Replica worker 2·커밋 순서 유지·내구성1/1.
- 500 VUser(300/150/5/5/40), ramp60초. ABBAAB 각3회. 슬롯마다 smoke→워밍업600초1회→GTID/유휴 안정화→본측정900초1회. HTTP 비교 구간은 마지막120초.
- 오류·coverage·입력·안전 실패는 중단 후 원복. JIT/TPS만 미통과한 본측정은 제외 사유를 보존하고 예정 슬롯 진행. 자동재시도 없음. B lag UNSTABLE은 재시도나 HTTP 측정 제외 사유가 아니다.
- 기존 복제 안정성 다섯 기준과 예약 작업 회피·메모리 기준 유지. 데이터는 누적하며 초기화하지 않는다. 앱 로그·실패 응답 XML·DB 오류 근거를 보존한다.

## 비교할 과거 기록

“새로운 부하테스트 방법 도입 전”은 최초 Replica 도입 시 발표한 단회 측정(평균42→11ms, TPS546.5→563.9)을 기준으로 해석한다. 과거 raw JTL과 기존 독립 감사 자료가 남아 있으면 현재와 같은 parent/redirect 분류로 재계산한 값도 별도 표시한다.

최초 기록의 Write/Read 풀은 50/80(단일 DB는50), 측정600초1회·끝120초 비교였고 워밍업 종료/JIT 확인이 부족했다. 현재는 풀20/30·워밍업분리·GTID/idle확인·900초측정·구성별3회·데드락수정코드다. 이미지/실행 메타데이터가 완전하지 않고 데이터가 누적되므로 결과 차이를 측정 방법 하나의 인과 효과로 해석하지 않는다. 최초73.8% 개선의 재현 여부와 현재 구성의 응답 성능·복제 안정성을 구분한다.

어제 수정 후 A/B 각2회 부분 결과(FAILED, B lag460/466초)는 독립 참고 이력으로 유지하며 이번 반복수에 합치지 않는다.

## 사전 점검과 완료 절차

09:55 KST 사전점검 통과. 전체25컨테이너 inventory, 원래 서비스22개의 정확한ID journal, DB각1CPU/1GiB, 복제ON/오류0/GTID동기화, 소스·이미지·입력일치를 확인했다. 가용물리약8.2GiB·커밋여유약31.9GiB, IntelliJ/JMeter/adoption 실행 프로세스는 관측되지 않았다. 실제 run 직전 다시 사전점검한다.

근거 `.tmp/replica-adoption-fixed/readiness/20260910T005521816399Z/`. 이미지 `sha256:cb215e007e159b821a47a46d39214e721926288ed8828147444b08d2f8d769e9`.

종료 후 전체 컨테이너/복제/GTID/행수/앱health/입력hash 원복 검증, 별도 완료 슬롯 원시 검산, 기존 Grafana 다크1000×500 대표패널 캡처와 초기기록 비교 보고서를 작성한다. 후처리 폴더는 `.tmp/replica-adoption-20260910-output/` 및 `.tmp/replica-adoption-20260910-analysis/`다. 측정 중 대량 JTL 분석·렌더링·빌드·추가 부하를 실행하지 않는다. 상태 확인은 사용자 선호대로 약10분 및 단계 전환·오류 중심으로 수행한다.

관련 기록: [어제 수정 후 부분 결과](replica-adoption-fixed-20260910.md), [기존 사전 기준](replica-adoption-fixed-20260909-plan.md), [초기 기록과 후속 재측정](replica-20260908.md).
