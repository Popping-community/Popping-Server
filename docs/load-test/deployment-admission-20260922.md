# 준비되지 않은 배포 후보 차단 — 2026-09-22

## 구현한 범위

새 앱이 준비되지 않았을 때 기존 앱을 유지하고 후보 투입을 거절하는 첫 단위를 구현했다. 기존 배포 경험·Redis 세션 장애 대응은 재사용할 자산으로 인정하고, 새로 검증한 범위와 구분했다.

- `src/main/resources/application-deployment.properties`: 선택적으로 적용하는 배포 설정. 업무 포트에 `/readyz`·`/livez`를 추가하고 readinessState를 사용한다. Graceful shutdown과 단계별30초를 명시한다.
- `scripts/deployment/admit_candidate.py`: 후보 MAINT·기존 앱 UP 확인 → 후보 업무 포트 readiness와 기존 로그인 쿠키의 보호 GET을3회 연속 확인 → 후보만 ready → HAProxy UP/L7OK 확인.
- 리디렉트를 따라가지 않고, 보호 화면 본문 marker도 확인한다. HTTP 프록시 환경변수는 사용하지 않으며 모든 접근은 명시적127.0.0.1로 제한한다.
- 직접 검사 실패 시 상태 변경 명령을 보내지 않는다. ready 이후 확인 실패 시 MAINT 복구 후 다시 조회한다. 복구 확인 불가 시 `admitted:null`과 최초·복구 오류를 함께 기록한다.

일반 운영 Compose·HAProxy·CI 배포 명령은 이번 단위에서 바꾸지 않았다. 앞선 PR/main 실행 조건 보강은 기존 로컬 변경으로 유지했다. 이 도구는 아직 원격 배포에 연결되지 않았다.

## 기존 상태 확인

사전 점검 당시 기존 로컬 앱 두 대는 일반 health200이었지만 readiness/liveness 그룹은404였다. `docker inspect`의 `Config.StopTimeout`은 앱과 프록시 모두1초였다. 일반적인10초 기본값을 해당 실행 컨테이너의 사실로 쓰지 않는다. 이 값이 저장된 원인이나 실제 원격 설정까지 확인한 것은 아니다.

기존 세션 transcript는 앱 stop 후20초 뒤 같은 쿠키로 보호 페이지200을3번 확인했다. 이번에 새 버전 전환 중 무손실 증거로 재해석하지 않았다.

## 격리 환경과 판정

Compose project `popping-admission-check-20260922`에 MySQL·Redis·old앱·candidate앱·HAProxy 총5개 컨테이너를 구성했다. DB와 Redis 데이터는 임시이며 원래 Popping DB를 사용하지 않았다. 업무 포트와 관리 포트를 분리하고 프록시는 업무 포트의 `/readyz`를 검사했다.

양쪽 앱은 **같은 현재 JAR**다. old/candidate는 역할명이며 서로 다른 버전이라는 의미가 아니다. JAR SHA는 `artifact.json`에 기록했다. JVM 기반 이미지 위에 JAR를 mount했으므로 불변 이미지 rollback을 입증한 실험도 아니다.

로그인은 old가 응답하는 프록시에서 한 번 수행했다. 발급된 SESSION을 디코드해 Redis의 정확한 세션 키가 존재하는지 확인했고, 같은 쿠키를 후보 직접 검사와 프록시 연속 요청에 사용했다. 익명 보호 페이지 요청은302였으며 로그인 페이지200을 성공으로 세지 않았다. 쿠키 값은 결과JSON에 남기지 않았다.

각 시나리오에서 게이트 시작1초 전부터 완료2초 후까지 보호 페이지 GET을5req/s로 예약했다. HTTP200과 보호 페이지 marker, 응답 backend를 검사하고 요청별 지연과 생성기 대기를 보존했다. 최대 처리량·운영SLO 측정이 아니다. 전체 회귀 테스트는 별도의 `portfolio_regression` DB를 쓰지만 같은 격리 MySQL 서버에서 일부 동시에 실행됐으므로 지연을 독립 성능 수치로 해석하지 않는다.

| 시나리오 | 실제 실패·성공 조건 | 게이트 결과 | 프록시 보호 GET | 후보 응답 | 기존 앱 |
|---|---|---|---:|---:|---|
| readiness 실패 | 후보 readyz503/UP, 직접 보호GET200 | exit1, MAINT 유지 |49건 모두정상|0건|동일ID·시작시각, UP|
| 세션 누락 | 후보 readyz200/UP, 빈쿠키 보호GET302 | exit1, MAINT 유지 |48건 모두정상|0건|동일ID·시작시각, UP|
| 정상 후보 | 같은Redis세션으로 readyz·보호GET 통과 | exit0, UP/L7OK |29건 모두정상|6건|동일ID·시작시각, UP|

총126건에서 오류·본문불일치가 없었다. 실패 시에는 HAProxy 후보 `stot` 증분도0이었고, 성공 시에는 후보응답 헤더와 `stot` 증가를 함께 확인했다. 직접 후보로 보내는 검사 요청은 존재한다. 위의 후보0건은 **프록시를 통한 업무 요청** 기준이다.

readiness 실패는 후보 전용 health group의 UP HTTP매핑을503으로 바꾼 주입이다. 정상 프로세스의 HTTP probe 실패를 시험한 것이며, 실제 DB장애·부팅실패·메모리부족을 재현했다고 하지 않는다. DOWN/OUT_OF_SERVICE도503으로 명시해 사용자 매핑 때문에 상태의 기본 오류코드가 바뀌는 것을 피했다.

후보를 정상 설정으로 재생성할 때는 Docker에서 확인한 새 주소를 HAProxy runtime에 명시적으로 반영했다. 이는 실험fixture 조치이며 운영 DNS 자동갱신을 검증한 것은 아니다. 기존 앱은 재생성하지 않았다.

## 테스트와 검토

- 제어 도구 단위테스트7개: readiness503, 로그인302, 정상투입, 프록시확인실패 후MAINT, 복구연결실패, MAINT미확인, 이미활성인후보보호.
- 기존 Java 전체 테스트175개 통과, failures/errors/skipped0. 격리 DB를 사용했다.
- 실제 Boot readiness 업무포트 동작은 위3개 런타임 시나리오에서 검증했다. Java175개에 이 시나리오나 Python7개를 섞어 세지 않았다.
- 독립검토에서 복구미확인을 정상거절과 구분할 것, 환경프록시 제거, 실패 원인의 실제 관측을 단언할 것을 지적받아 반영했다.
- 구현을 단순화할 때 기존앱제어·Docker교체·DNS정책을 gate모듈에 넣지 않았다. 실험fixture와 후보투입판정을 분리했다.

초기 fixture setup에는 Python의 `http()` helper가 `http` 모듈을 가리는 오류가 있었다. 앱시작·probe확인은 끝났지만 로그인전 실패했다. 별칭으로 수정하고 이미생성된환경에서 로그인단계만 재개했다. 이 실패는 `setup-failure-01.json`으로 보존했고 애플리케이션실패나 통과시나리오로 집계하지 않았다. 처음 Docker·Gradle의권한접근실패도 승인후재시도했으며 부하시험실패로 집계하지 않았다.

## 남은 경계와 다음 단위

readiness200은 JVM warm, 모든DB/Redis기능, 모든사용자권한의 정상여부를 뜻하지 않는다. 한 보호GET은 그기능의 세션경로만 보강한다. 후보직접검사와 실제투입 사이 상태변화도 가능하므로 지속HTTP체크가 있어도 오류0을 보장하지 않는다.

이번에는 기존앱을 drain/stop하지 않았다. 컨테이너 종료유예35초와 Spring단계30초는 설정됐지만 **진행중요청완료·강제종료복구·롤링배포·구버전rollback·단일호스트HA는 미검증**이다. 다음단위는 단일앱이 감당할 사전선언부하에서 신규유입차단과 진행중요청완료를 확인하고, 이후 한대씩교체·이전버전복귀를 검증하는 것이다. 기존댓글캐시의3초쿠키/5초TTL한계도 그대로 남는다.

## 자료

원본 폴더: `_workspace/deploy-safety/2026-09-22/gate-v1/`.

- `plan.md`, `review.md`: 사전조건·공식문서대조·설계검토.
- `prepare.py`, `rig.py`, `compose.yml`, `bad.yml`, `haproxy.cfg`: 격리구성과 실패주입·검증.
- `setup.json`, `artifact.json`: 세션검증·앱식별. 임시쿠키파일은 정리때삭제.
- `gate-*.json`, `scenario-*.json`: 판정상세와126개HTTP응답메타데이터.
- `candidate-address-update.json`: 후보재생성후주소갱신기록.
- `full-suite-results.json`: 전체테스트집계.
- `runtime-versions.json`: 실제 HAProxy·MySQL·Redis 버전과 컨테이너 식별.
- `cleanup.json`: 소유 label을 확인한 임시 컨테이너 5개와 전용 네트워크 제거. 원래 컨테이너 ID들은 계속 실행 중임을 확인했고 임시 세션 쿠키 파일도 삭제했다.
- `validation.json`: 결과를 다시 읽어 검증한 요약과 소스·JAR·원본 SHA-256.

이번 작업에서 커밋·push·병합·원격 배포는 하지 않았다.

공식근거: [Spring Boot3.4 probes·health groups](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes), [HAProxy2.8 disabled 설정](https://docs.haproxy.org/2.8/configuration.html#5.2-disabled), [HAProxy runtime 관리](https://docs.haproxy.org/2.8/management.html#9.3).
