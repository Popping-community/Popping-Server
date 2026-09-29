# Popping 이전 프로세스 유지와 재생성 비교 — 2026-09-23

## 결론과 선택

로컬 격리 실험에서 복귀 검증 완료 시간 중앙값은 재생성 **28.236초**, 기존 프로세스 유지 **3.221초**였다. 세 쌍 모두 유지 방식이 빨랐다. 이는 이전 프로세스를 유지하는 선택을 검토할 실측 근거다. 운영 적용은 하지 않았으며, 장기 보존 정책까지 채택한 것은 아니다.

다음 후보는 **정해진 기간 동안 이전 앱을 MAINT로 유지하고, 복귀와 만료 종료의 조건을 명시하는 로컬 절차**다. 무기한 유지나 두 버전의 동시 트래픽 처리는 제외한다. 메모리 여유가 없거나 허용 복구 시간이 충분히 길면 기존 이미지 재생성이 더 단순한 대안이다. 운영 보존 시간은 이번 10초 관측으로 결정하지 않는다.

## 사전 조건과 측정 계약

- [사전 계획](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/plan.md)에 따라 쌍별 순서를 바꿨다: cold-1, retained-1, retained-2, cold-2, cold-3, retained-3. 완전 무작위화가 아니다.
- 매회 독립 Compose 프로젝트, 새 MySQL/Redis, 동일 V1/V2 이미지 SHA. 이미지는 보호 페이지 marker만 다르고 앱 클래스·라이브러리·스키마가 같다.
- V1 로그인 1회와 보호 GET 5회, V2 admission, V1 MAINT 전환과 scur/qcur=0 연속 3회 확인까지 동일하다. 완전한 JVM 워밍업을 증명하지 않는다.
- 재생성 군은 V1 SIGTERM 종료 후 대기, 유지 군은 동일 ID/StartedAt의 V1을 MAINT로 계속 실행. 양쪽에 10초 관측 구간을 둔다.
- V2 댓글 쓰기 완료 후 정확히 소유한 V2에 SIGKILL을 주입하고 exited137 → DOWN → MAINT를 확인한다.
- 양쪽 모두 같은 recover_server.py, SESSION, 기대 V1 marker, 연속 성공 3회 게이트를 쓴다. 재생성 군은 V1 생성·기동 시간을 포함한다. 이전 실험의 의도적인 잘못된 marker 지연은 없다.
- **주 지표는 장애 주입 명령 호출 시작부터, 게이트 반환 후 동일한 보호 GET의 V1 응답 완료까지 호스트 monotonic 시간**이다. 정확한 가용성 복구 순간이나 운영 RTO가 아니다.

## 반복 결과

| 비교 쌍 | 종료 후 재생성 | 실행 상태 유지 | 시간 차이 |
|---|---:|---:|---:|
| 1 | 28.673초 | 3.221초 | 25.452초 |
| 2 | 28.236초 | 3.320초 | 24.916초 |
| 3 | 28.152초 | 2.877초 | 25.274초 |

각 방식 3회뿐인 기술적 비교다. 분포·유의확률·운영 상한을 추정하지 않으며 전날의 다른 조건 결과와 합치지 않는다. 각 쌍의 차이는 인접 실행 두 건의 차이로, 공유 호스트의 시간 변화가 통제된 인과 효과는 아니다.

## 유지 비용

| 유지 실험 | 관측 구간 | 메모리 평균 / 최대 | 누적 CPU 증가 | 한 코어 기준 비율 |
|---|---:|---:|---:|---:|
| retained-1 | 10.012초 | 410.9 / 411.3 MiB | 0.238초 | 2.38% |
| retained-2 | 10.016초 | 410.9 / 411.8 MiB | 0.284초 | 2.84% |
| retained-3 | 10.000초 | 408.7 / 409.3 MiB | 0.293초 | 2.93% |

각 실행에서 cgroup v2의 memory.current와 cpu.stat usage_usec를 11번 읽었다. 시간은 첫·마지막 docker exec 완료 사이, CPU는 누적 사용 시간의 차이다. 메모리는 컨테이너 cgroup 값으로 JVM heap이나 전용 RSS가 아니며 캐시를 포함할 수 있다. docker exec 측정 자체의 비용도 포함된다. 짧은 초기 실행 구간이므로 장기 유휴 CPU·총 호스트 자원·클라우드 비용으로 환산하지 않는다. 재생성 군의 이전 앱은 exited 상태였고, 이 군에 대해 자원 측정값 0을 만들어 넣지 않았다.

## 장애 관측과 데이터

| 실험 | 기록 수 | HTTP 200 | HTTP 502 | HTTP 503 | 전송 오류 |
|---|---:|---:|---:|---:|---:|
| cold-1 | 90 | 13 | 0 | 76 | 1 |
| retained-1 | 12 | 11 | 0 | 0 | 1 |
| retained-2 | 13 | 12 | 0 | 0 | 1 |
| cold-2 | 89 | 13 | 0 | 75 | 1 |
| cold-3 | 97 | 12 | 1 | 84 | 0 |
| retained-3 | 21 | 13 | 0 | 8 | 0 |

이 표는 장애 전후 응답 뒤 0.25초 쉬는 단일 관측기의 기록이다. 요청 timeout은 다음 관측을 지연시킨다. 고정률 부하나 가용성 비율이 아니다. 별도 완료 검증 GET은 표에 합치지 않는다. 두 방식 모두 오류가 관측되었으므로 무중단으로 표현하지 않는다.

6회 모두 처음 발급받은 SESSION으로 복귀 V1 보호 페이지를 읽었다. V2가 완료한 댓글 ID 1을 V1에서 조회하고, V1의 새 댓글 ID 2와 부모 comment_count=2를 확인했다. 유지 군은 원래 V1 ID/StartedAt이 같고, 재생성 군은 새 컨테이너에서 같은 이미지 ID를 확인했다. 진행 중 쓰기 보존, 기능 변경·DB migration의 역호환, 캐시 장기 노후화, 스케줄러 중복 수행, 최대 부하와 호스트 장애는 검증하지 않았다.

## 실행·검토·테스트 이력

- 실제 6회 실행 소스: [rig.executed.py](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/rig.executed.py). 실행 중 로드된 소스를 보존했다.
- 검토 후 [rig.py](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/rig.py)는 부분 자원/park 기록 즉시 보존, sampler 종료 실패 시 cleanup 후 캠페인 중단을 보강했다. 성공 경로 측정은 바꾸지 않았다. 6회 모두 passed=true, error/sampler_error 없음, 자원 11개, cleanup 완료를 사후 검증했다.
- 배포 도구 Python 단위 테스트 **23건 통과**, 수정한 fixture 실패 처리 테스트 **2건 통과**. 이 2건은 수정본의 실패 처리 테스트이며 6회 Docker 실행 소스와 구분한다.
- Java 앱 코드와 운영 CI는 이번에 수정하지 않았다. 이번에 Java 175건을 다시 실행한 것으로 기록하지 않는다.
- simplify 검토: 기존 이미지·HTTP·소유권·recovery gate를 재사용했다. 실험 순서를 숨기는 새 공통 프레임워크는 추가하지 않았다. 소스/분석/문서 생성은 별도 파일로 분리했다.
- 실행마다 소유한 컨테이너 5개만 정리, 기존 Popping 컨테이너 10개 ID 보존, SESSION 파일 삭제 확인. 정확한 이미지와 실행 기록은 보존했다.

## 다음 작업의 범위와 중단 조건

로컬 배포 절차에 제한된 보존 구간을 넣는다면 먼저 선택할 조건은 다음과 같다. (1) 신규 앱의 실제 요청 확인 후 이전 앱을 MAINT로 보존, (2) 보존 기간 내 장애 때만 동일 복귀 게이트 사용, (3) 기간 만료 때 신규 앱 상태·이전 앱 요청/큐·컨테이너 소유권을 재확인하고 종료, (4) 상태를 확인할 수 없으면 자동 종료를 중단하고 원인을 기록. 타이머와 복귀가 겹치는 상황은 단일 제어 주체로 직렬화해야 한다.

완료 기준은 보존 중 복귀·정상 만료·만료 직전 신규 앱 장애·컨테이너 교체 감지 네 시나리오의 기록이다. 메모리 여유 또는 동일 호스트 내 단일 제어를 확보하지 못하면 구현을 확대하지 않는다. 운영에 도입하려면 별도로 단일 신규 앱의 목표 부하 수용, 스케줄러/비동기 작업, 스키마 호환, 배포 권한과 영속 상태 복구를 판단해야 한다.

## 원자료

- [집계 JSON](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/summary.json)
- [재계산 스크립트](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/analyze.py)
- [SHA-256 manifest](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/manifest.json)
- [cold-1 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-1/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-1/cleanup.json)
- [retained-1 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-1/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-1/cleanup.json)
- [retained-2 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-2/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-2/cleanup.json)
- [cold-2 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-2/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-2/cleanup.json)
- [cold-3 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-3/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/cold-3/cleanup.json)
- [retained-3 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-3/scenario.json) · [정리 확인](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/retained-3/cleanup.json)
