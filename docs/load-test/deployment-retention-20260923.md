# Popping 이전 프로세스 보존 기간과 종료 조건 — 2026-09-23

## 결과

새 [retain_previous.py](../../scripts/deployment/retain_previous.py)는 이미 MAINT인 이전 앱을 일정 기간 보존하고, 만료 후 세션/큐·새 앱 업무 응답·두 컨테이너 신원을 확인한 뒤 SIGTERM을 보낸다. 한 호출자에서 동기적으로 실행하며 자동 복귀, 별도 종료 타이머, 데몬, 운영 CI 적용은 없다.

Docker 4개 격리 시나리오와 Python 단위 테스트 36건(새 보존 관련 13건)이 통과했다. 정상 만료에서만 종료 callback 1회, 나머지는 0회였다. 이후 보호 GET은 각 6건, 총24건 정상이다. 이는 장애 전후 모든 요청이 성공했다는 뜻이 아니다.

| 시나리오 | 종료 callback | 종료 확인 | 후속 보호 GET | DB ID → count |
|---|---:|---|---|---|
| 정상 만료 | 1회 | True | v2 6/6 정상 | 1, 1 |
| 보존 중 장애 | 0회 | False | v1 6/6 정상 | 1, 2, 2 |
| 만료 후 업무 검사 시점 장애 | 0회 | False | v1 6/6 정상 | 1, 2, 2 |
| 이전 컨테이너 실제 교체 | 0회 | False | v2 6/6 정상 | 1, 1 |

장애 전후 별도 sampler에서는 보존 중 장애에서 timeout1건, 만료 시점 장애에서 HTTP503 15건이 관측됐다. 후자의 종료 거절 원인은 별도 업무 검사 GET의 timeout이었다. 배경 관측 오류와 제어 함수 거절 원인을 구분한다. 정상 만료와 교체 케이스의 sampler는 각각18건/20건 모두200이었다. 응답 후0.25초 쉬는 관측이며 고정률 부하나 가용성 통계로 해석하지 않는다.

## 선택한 구조와 대안

이전 [비교 실험](deployment-recovery-compare-20260923.md)은 프로세스 유지의 복귀 시간 이점과 자원 점유를 보여줬다. 이번 문제는 이전 앱을 언제 종료할 수 있느냐다. 단순 sleep 후 종료하면 그 사이 신규 앱 장애나 컨테이너 교체를 놓친다. 별도 타이머와 복귀 제어기를 두면 서로 독립적으로 상태를 바꾼다. 현재 로컬 실험은 한 동기 함수가 판단하고 반환한 뒤 호출자가 별도의 복귀를 수행하는 방식을 선택했다.

절차는 다음과 같다.

1. 호출자가 V2 admission/실제 동작을 확인하고 V1을 MAINT로 전환한다. 두 OwnedContainer의 ID·StartedAt과 기대 이미지 SHA를 고정한다.
2. 최소 보존 기간 동안 V1 MAINT·stot 불변, V2 건강 상태, 양쪽 프로세스·주소·이미지를 재검증한다.
3. 만료 후 V1 scur/qcur=0을 연속3회 확인한다. V2의 같은 SESSION 보호 GET과 기대 marker가 통과해야 한다.
4. 프록시 상태·요청/큐·신원을 다시 읽고 검사 기한을 확인한다. 통과하면 정확한 이전 컨테이너에만 SIGTERM을 보낸다.
5. 실패하면 종료 판단을 중단한다. V2의 실제 exited와 MAINT를 별도로 확인한 경우에만 호출자가 기존 recover_server.py로 복귀한다. 단순 health 실패만으로 복귀시키지 않는다.

## 시간과 상태 의미

이번 보존 시간 **3초**는 테스트 조건이며 운영 TTL이 아니다. 정상 종료 callback은 보존 시작 후 **4.035초**에 시작했다. hold는 최소 기간이고, 이후 검사에는 drain_timeout=10초, 종료 확인에는35초를 부여했다. 개별 HTTP/Docker 호출 시간이 더해지므로 엄격한 전체 wall-clock SLA가 아니다.

| stopped | stop_invoked | 의미 |
|---|---|---|
| false | false | 종료 callback 미호출 |
| false | true | SIGTERM 후 관측 기한 내 exited를 확인하지 못함 |
| null | true | callback 시도 뒤 결과 확인 중 오류로 불명확 |
| true | true | exited 확인. 정상 종료 여부는 exit code/OOM 별도 확인 |

정상 만료는 exit code143 또는0과 OOM=false까지 대조했다. 강제 종료 fallback은 없다.

## 거절 이유까지 확인

- 보존 중 장애: 주입과 제어 함수 반환이 만료 시각 전이며 업무 검사에는 진입하지 않았음을 원자료로 확인했다. V1 동일 ID/StartedAt 보존 후 복귀했다.
- 만료 시점 장애: 업무 검사 callback 진입 후 V2 SIGKILL을 주입했다. **마지막 확인 이전의 장애**를 거절한 실험이다. 마지막 확인과 SIGTERM 사이의 경쟁 조건을 제거했다는 증거가 아니다.
- 컨테이너 교체: fixture가 직접 기존 V1을 종료·재생성했다. V2 업무 검사200/marker=true가 먼저 성공하고, 이후 **고정된 이전 ID의 docker inspect 실패**로 거절된 것을 대조했다. 일반적인 업무 검사 실패를 신원 교체 감지로 잘못 인정하지 않았다. 제어 함수의 stop callback은0회, 교체된 새 V1은 실행 상태로 남았다. 원래 V1은 fixture가 교체했으므로 원래 프로세스 보존을 주장하지 않는다.
- 장애2건 모두 처음 발급한 SESSION으로 복귀 V1에서 V2 댓글 ID1을 읽고 새 댓글 ID2를 작성, DB count2 확인. 정상/교체 시나리오는 ID1·count1을 확인했다.

## 검토와 검증

codex-system에 따른 독립 Codex 리뷰를 받았다(Claude 검토로 표기하지 않음). 만료 경계의 음수 sleep 가능성을 clamp로 수정했고 실제 RetainedPair의 이미지·StartedAt·상태·주소·loopback 바인딩 불일치 테스트를 보강했다. 종료 결과 unknown과 timeout을 구분한다. 실제 교체 케이스는 리뷰에 따라 [분석 스크립트](../../_workspace/deploy-safety/2026-09-23/retention-v1/analyze.py)에서 성공한 업무 검사 및 정확한 이전 ID 조회 실패를 추가 검증했다. 실행 중 fixture는 수정하지 않았다.

새 테스트는 시간 만료 정상 동작, 중간/최종 survivor 실패, identity drift, 잘못된 marker, busy/queue, 새 트래픽, 검사 지연, 종료 응답 불명확, 잘못된 기간, 종료 관측 timeout, 실제 신원 검증을 포함한다. Java 소스 수정이 없어 이번에는 Java 전체 테스트를 재실행하지 않았다.

simplify 검토: 새 영속 상태 머신이나 데몬 대신 기존 OwnedContainer, Runtime, 복귀 게이트를 재사용했다. 측정 fixture의 이벤트 및 결과 분석은 제품 코드와 분리했다.

## 한계와 다음 단계

최종 survivor 확인과 SIGTERM은 원자적이지 않다. 확인 직후 새 앱이 죽으면 이전 앱도 종료될 수 있다. 보존으로 모든 장애를 막거나 무중단을 보장하지 않는다. 다중 제어기 잠금, 프로세스 재시작 후 이어하기, 장기 캐시 노후화, 스케줄러·비동기 작업, schema migration, 목표 부하 수용은 이번 범위 밖이다. 호출 중단 후 상태는 사람이 확인해야 한다.

다음은 추가 제어 기능을 늘리기 전에 지금 만든 admission→보존→종료/복귀 절차를 한 실행 문서로 정리하고, 실제 운영 구성과의 차이를 점검하는 것이다. 실제 CI 연결은 프로젝트 소유권 제한과 단일 제어·실패 후 복구 정책을 별도로 설계한 뒤 결정한다. 3초 실험을 운영 기본값으로 옮기지 않는다.

매회 독립 DB/Redis와 기존 immutable 이미지 사용, 소유한 컨테이너5개만 정리, SESSION 파일 삭제, 원래 Popping10개 ID 보존. 기능·스키마가 같은 marker 변경 이미지이므로 역호환성 증명이 아니다.

## 근거

- [사전 계획](../../_workspace/deploy-safety/2026-09-23/retention-v1/plan.md)
- [원자료 집계](../../_workspace/deploy-safety/2026-09-23/retention-v1/summary.json)
- [실행 fixture](../../_workspace/deploy-safety/2026-09-23/retention-v1/rig.py)
- [원자료/소스 SHA-256](../../_workspace/deploy-safety/2026-09-23/retention-v1/manifest.json)
