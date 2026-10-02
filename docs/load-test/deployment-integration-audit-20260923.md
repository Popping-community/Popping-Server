# Popping 배포 검증과 현재 구성의 차이 — 2026-09-23

## 판단

배포의 개별 안전 조건은 로컬에서 검증했지만, 현재 CI에 그대로 연결할 수 있는 운영 배포 도구는 아니다. 우선순위는 **검증한 빌드 이미지와 투입할 이미지를 일치시키는 계약**이다. PR과 배포의 조건 분리는 이미 있으므로 다시 만드는 작업으로 잡지 않는다.

최초 대조 작업은 실행서·대조 문서·읽기 전용 근거 수집까지였다. 같은 날 Claude 검수 후 격리 실험용 소유권 guard와 투입 전 신원 검사 adapter를 추가했다. 앱·HAProxy·운영 Compose·CI를 변경하거나 원격에 접속하지 않았다. 기존 컨테이너10개 ID는 관측 및 후속 실험 전후 보존했다.

## 근거의 세 층

1. **현재 작업 트리:** 아래 소스 파일에서 직접 확인했다. 미커밋 변경이 있어 원격 GitHub나 원격 서버에 같은 내용이 있다고 가정하지 않는다.
2. **현재 로컬 Docker:** [observation.json](../../_workspace/deploy-safety/2026-09-23/runbook-v1/observation.json)의 확인 시각, ID, 제한된 inspect 필드와 health GET으로 확인했다. 전체 환경변수·자격증명·health 상세는 저장하지 않았다.
3. **원격 운영 호스트:** 이번에 확인하지 않았다. 원격 compose, image digest, 실행 JAR, 적재한 HAProxy 설정, 부하/자원과 배포 이력은 미확인이다.

## 구성 대조

| 항목 | 확인한 사실과 근거 | 의미 / 필요한 판단 |
|---|---|---|
| PR/배포 조건 | [.github/workflows/build.yml:85](../../.github/workflows/build.yml#L85), 93,98의 로그인·Jib push·SSH는 main push에만 실행 | 경계는 있음. 같은 job의 step 조건이며 별도 job은 아님. 원격 실행 성공을 확인한 것은 아님 |
| 릴리스 선택 | [build.gradle:27](../../build.gradle#L27)의 Jib 대상은 latest·시각 태그. [docker-compose.yml:123](../../docker-compose.yml#L123),208은 redis-session-optin-20260818 태그 | 이 소스 조합에는 이번 빌드와 compose 선택 이미지를 연결하는 전달 절차가 없음. 원격도 오래된 이미지를 배포한다고 단정하지 않음 |
| 실제 로컬 이미지 | 앱 두 개의 image ID는 a3246ccd…b603로 동일, JAR bind mount 없음 | 로컬 이미지 동일성만 확인. 지금 작업 트리/원격 registry digest와의 동일성은 별도. tag와 local config image ID, registry manifest digest는 서로 다른 식별자 |
| 배포 명령 | [workflow:105](../../.github/workflows/build.yml#L105) 이후 전체 docker-compose pull / up -d | admission·직렬 교체·검증된 복귀 호출 없음. 실제 두 앱이 항상 동시에 중단된다고 단정하지 않음 |
| 동시 실행 | 해당 workflow에 concurrency 정의 없음 | 로컬 실험의 단일 caller 가정을 GitHub/SSH 환경이 보장하지 않음. GitHub 직렬화만으로 수동 SSH까지 배제되지는 않음 |
| 프록시 소스 | [haproxy.cfg:17](../../haproxy/haproxy.cfg#L17): app_servers/app1,app2의 TCP check. readiness HTTP·admin socket·resolvers·후보 disabled 정의 없음 | 실험 apps/old,candidate와 다름. 현재 파일이 실행 프로세스에 그대로 적재됐는지는 이번에 증명하지 않음 |
| readiness 활성화 | [application-deployment.properties](../../src/main/resources/application-deployment.properties)는 opt-in. [build.gradle:43](../../build.gradle#L43)의 프로파일은 dev | 보이는 Compose에 deployment 활성화가 없음. secret 기반 최종 설정은 미확인. profile 추가만으로 전체 프로브/업무 경로 완성이라 하지 않음 |
| 현재 로컬 health | 두 앱 관리 포트8081/8082의 /actuator/health는200/UP, readiness와 liveness 하위 경로는404 | 관리 health가 정상인 것과 업무 포트의 readyz 검증은 다름. 이번에 업무 포트 /readyz를 직접 검사하지 않았음 |
| 포트·소유권 | 도구는 loopback 업무 포트9091 및 격리 project label을 전제. 로컬 앱은 **host8081 → container8081**, **host8082 → container8081**, project=popping-server | 현 원본 대상은 제어 도구의 지원 범위 밖. 이름 제한을 풀고 쓰는 것으로 해결하지 않음 |
| 종료 유예 | Compose 앱에 stop_grace_period 명시 없음. 로컬 Config.StopTimeout=1. 실험은35초, opt-in Spring 단계30초 | 이 로컬 상태에 Docker10초 기본값 가정을 적용하지 않음. 실제 종료 명령 override·Spring 유효 설정·중단 결과는 미측정 |
| 재시작 정책 | 로컬 앱 restart=always. 격리 이미지 교체 fixture는 자동 재시작을 지정하지 않음 | 실험의 SIGKILL→exited 유지 전제를 현 구성에 복사할 수 없음. 동일 ID의 StartedAt 변경 거절은 구현·fake inspect 단위 테스트로 확인했으며 실제 Docker restart 시연은 아직 없음 |
| 메모리 | 로컬 앱 제한 각2GiB, 비교 fixture640MiB·CPU1 | 유지 비용 약409~411MiB와 복귀 시간은 해당 fixture 관측. 원본 앱의 여유/운영 비용으로 대입하지 않음 |
| admission 책임 | [admit_candidate.py](../../scripts/deployment/admit_candidate.py)는 이미지/컨테이너 인자를 받지 않음 | caller가 정확 이미지·포트 연결을 admission 전에 확인해야 함. marker만으로 소스 출처나 ownership을 보장하지 않음 |

## 실제 서비스에 남는 업무

구성 원문 보완: 실험의 [Compose](../../_workspace/deploy-safety/2026-09-22/release-v1/compose.yml)는 앱640MiB·CPU1·35초 종료 유예를 명시하고 restart를 지정하지 않는다. [opt-in 설정](../../src/main/resources/application-deployment.properties)은 Spring 단계30초다. 약409~411MiB는 [복귀 비교 원자료](../../_workspace/deploy-safety/2026-09-23/recovery-compare-v1/summary.json)의 유지 군 메모리 평균 범위다. 이 자료는 Claude 검수 패킷에 모두 포함하지 않았지만 작성 시 직접 읽어 대조한 근거이며, 원격 사실은 아니다.

| 코드 근거 | 확인한 구현 | 아직 검증하지 않은 것 |
|---|---|---|
| [ViewCountService.java](../../src/main/java/com/example/popping/service/ViewCountService.java) | 최초 대조 때 LongAdder 누적·30초/PreDestroy flush. 이후 [동시 인계 유실 수정](view-count-transfer-20260923.md)으로 Long merge/remove로 변경 | scur=0 이후 진행 중 flush 완료·종료 시 DB 실패·SIGKILL 손실은 여전히 미검증. HTTP 카운트로 모두 완료 판정 불가 |
| [LikeCountReconcileScheduler.java:21](../../src/main/java/com/example/popping/scheduler/LikeCountReconcileScheduler.java#L21) | 매일04시 게시글/댓글 재계산 작업 | 두 프로세스/버전에서 겹칠 때 잠금·처리 비용·정합성 영향 |
| [TempImageCleanupScheduler.java:17](../../src/main/java/com/example/popping/scheduler/TempImageCleanupScheduler.java#L17), [ImageService.java:192](../../src/main/java/com/example/popping/service/ImageService.java#L192) | 매일00시 TEMP 이미지 조회, S3와 DB 삭제 | MAINT여도 작업은 별도. 중복 실행·배포 중 외부 부작용을 검증하지 않음 |
| [WebSocketConfig.java:19](../../src/main/java/com/example/popping/config/websocket/WebSocketConfig.java#L19),29 | /ws SockJS와 프로세스 내 simple broker | 연결 유지·재연결·다른 인스턴스 구독 전달·장기 연결 종료 정책. Redis 세션과 구독 전달은 별개 |

이는 **소스 구현 존재**의 증거다. 현재 실행 이미지가 이 작업 트리의 클래스와 같다는 검증은 하지 않았고, 중복 실행 사고가 실제 발생했다는 뜻도 아니다. 이러한 경로가 배포 검증에서 빠져 있다는 판단이다. 지금 바로 Kafka나 분산 스케줄러를 도입하는 근거로 확대하지 않는다.

## 순서와 다음 완료 기준

### Claude 검수 후 축소한 첫 단위: 투입 전 불일치 차단

문제: 현재 소스에 빌드가 내보낸 태그와 Compose가 사용하는 태그가 다르고, admission 자체에는 image/port ownership 검증이 없다. 후보가 응답해도 이번에 검증한 산출물인지 caller가 별도로 입증해야 한다.

초기 계획은 아래 항목까지 포함했으나, **2026-09-23 Claude Opus 5 검수 후 manifest 체계는 보류**했다. 먼저 기존 RetainedPair 검사를 재사용하는 [verified_fixture.py](../../scripts/deployment/verified_fixture.py)와 [새 실행 adapter](../../_workspace/deploy-safety/2026-09-23/guarded-retention-v1/rig.py)를 추가했다. 이전 네 제어 도구와 원래 측정 fixture의 소스는 보존했다. 변경 내용·검증 범위는 [검수 반영 기록](../../_workspace/deploy-safety/2026-09-23/claude-review-v1/decision.md)에 있다.

현재 반영한 범위는 다음과 같다.

- 새 adapter에서 생성 전 실패 시 cleanup을 생략하고, 각 생성 시도 후 관측 ID를 추적해 미관측 ID가 있으면 down을 거절한다. 부분 생성 실패도 ID 추적을 시도하며, 추적 불명 시 정리를 차단한다. 단일 caller 전제이며 잠금은 아니다.
- 기존 RetainedPair.verify를 admission 앞에서 호출하고, 검증 대상과 실제 admission의 서버·URL·admin 포트 인자를 연결한다. 이미지·StartedAt·포트 불일치와 사라진 컨테이너는 단위 테스트에서 admission callback 및 제어 변경 호출0회로 확인했다. 정상 연결은 Docker1건으로 확인했다.

향후 확장 후보였던 원래 계획은 다음과 같으며, 이번에 모두 구현한 것이 아니다.

- manifest에 소스 commit과 dirty 여부, 빌드 입력 fingerprint, 검증한 artifact hash, 선택한 이미지 식별자, 기존 이미지, 실행 container ID/StartedAt/project/service/업무 포트 연결을 기록한다. dirty 작업 트리를 commit SHA 하나로 대표하지 않는다.
- registry 기반이면 manifest digest 및 해당 플랫폼의 실행 config image ID 관계를 기록한다. 로컬 fixture에는 registry digest가 없으면 미확인으로 남기고 local image ID를 digest라고 바꾸어 부르지 않는다.
- caller에서 기대 이미지/포트/프로세스를 확인한 뒤 admission을 허용한다. 이미지는 태그를 다시 resolve하는 시점 차이까지 고려해 고정한다. 기존 도구의 보호 제약은 유지한다.
- 정상 이미지, 다른 image ID, 재시작된 동일 container ID, 다른 컨테이너로 연결된 포트의 네 조건을 검증한다. 불일치 시 **ready/MAINT 변경·종료 명령0회**, 기존 앱 응답·ID 보존이 완료 기준이다.
- 새 이미지 빌드나 registry push, 원격 SSH 배포, 운영 CI 수정은 이 첫 검증에 포함하지 않는다. 정확한 source→build 근거를 확보하지 못하면 그 지점은 미확인으로 유지하고 다음 단계로 넘어가지 않는다.

그다음 readiness/프록시/주소 갱신/종료 유예를 격리된 실제 구성에 맞춰 연결한다. 이어 재시작 정책·단일 제어·중단 후 수동 복구 계약, WebSocket/배경 작업, 현 단일 앱 부하와 스키마 호환을 확인한다. 원격 적용 전에는 원격의 실제 파일·이미지·정책을 읽기 전용으로 확인해야 한다.

추가 자동화보다 **무슨 이미지를 실행하는지 식별할 수 있는 것**을 먼저 하는 이유는, 그 연결이 없으면 이전의 readiness·드레인·복귀 검증이 다른 산출물에 적용될 수 있기 때문이다.

## 자료와 범위

- [통합 실행서](deployment-runbook-20260923.md)
- [읽기 전용 관측](../../_workspace/deploy-safety/2026-09-23/runbook-v1/observation.json), [수집기](../../_workspace/deploy-safety/2026-09-23/runbook-v1/collect.py)
- 최초 대조는 읽기 전용이었다. 이후 검수 반영에서는 도구 단위 테스트46건, adapter 테스트3건, 격리 Docker 정상 만료1건을 확인했다. Docker4건은 [직전 retention 검증](deployment-retention-20260923.md)의 결과이며 이번에 반복하지 않았다.
- 새 Docker 실행에는 배경 요청 samples 저장 누락이 있었다. 실행 소스를 별도로 보존하고 저장 코드를 수정했으며 실패 경로 기록 테스트를 추가했다. 이번 실행의 배경 요청 오류 건수나 무중단 성공은 주장하지 않는다. 세부 근거와 한계는 검수 반영 기록에 있다.
- 이번 보강은 별도 성과 글로 확대하지 않고 검수 반영 기록과 실행서에 남긴다.
