# 댓글 첫 페이지의 교차 앱 정확성 — 2026-09-22

> 이 문서는 같은 Primary에서 원인을 분리한 최초 실험과 당시 OFF 결정을 보존한다. 후속 [Replica·비용 비교](comment-sticky-routing-20260922.md)를 거쳐 현재 소스는 [작성자 우회 + 일반 조회 ON·TTL 5초](comment-cache-hybrid-20260922.md)로 변경했다. 아래 OFF 상태는 현재 설정이 아니다. 원격 배포는 하지 않았다.

## 결정

`app.cache.comment-first-page.enabled` 기본값을 `false`로 두어 댓글 첫 페이지의 로컬 캐시를 기본 비활성화한다. 게시판 첫 페이지와 게시글 상세 캐시는 유지한다. `true`는 기존 단일 JVM 캐시 동작을 비교할 때만 명시적으로 선택한다. 이 옵션을 켜면 확인한 교차 앱 stale 문제가 다시 생길 수 있다.

성능 최적화나 분산 무효화 구현이 아니다. 댓글 페이지를 매 요청마다 기존 트랜잭션/DB 조회 경로에서 구성하도록 선택했다. 동작 변경 지점은 `CacheConfig`이며, `CommentService.getFirstPageCommon`의 기존 cache-null 경로와 `CacheEvictListener`의 null 방어를 그대로 이용한다.

## 기준선과 범위

- 현재 소스로 빌드한 JAR, 앱 JVM2개, 격리 MySQL8.4.9 Primary1개. 읽기·쓰기 모두 동일 DB. 원래 Popping DB를 사용하지 않았다.
- Docker 기존 앱 이미지는 JVM 실행 기반으로만 사용하고 새 JAR를 별도 mount했다. 과거8월 앱의 결과로 대체하지 않았다.
- 별도 설정 파일만 읽도록 `spring.config.location`을 지정하고 테스트 전용 계정·데이터를 사용했다. Redis/Replica/HAProxy를 경로에서 제외했다. Redis health indicator도 이 리그에서만 비활성화했다.
- 확인 계약: 성공한 작성·삭제 응답 이후 시작한 새 댓글 API 조회가, 추가 쓰기가 없는 상태에서 양쪽 앱 모두 DB의 댓글 ID와 개수와 일치할 것. 단순 HTTP200이나 개수만으로 통과시키지 않는다.
- 다른 캐시의 정합성, Replica 최신성, 전체 서비스의 선형화 가능성, 실제 운영 성능·배포 적용은 검증 대상이 아니다. Replica를 읽는 실제 구성은 캐시 OFF만으로 최신성을 보장하지 않는다.

## 실제 결과

| 관측 지점 | DB 댓글 수 | 기존 앱1 / 앱2 | 전체 캐시 OFF 대조 | 댓글 캐시만 OFF 수정본 |
|---|---:|---|---|---|
| 빈 페이지 양쪽 warm | 0 | 0 / 0, ID 일치 | 일치 | 일치 |
| 앱1 작성 성공 후 | 1 | 1 / 0, 불일치 | 일치 | 일치 |
| 앱2 작성 성공 후 | 2 | 1 / 2, 불일치 | 일치 | 일치 |
| 앱1 추가 작성 후 | 3 | 3 / 2, 불일치 | 일치 | 일치 |
| 잘못된 입력400 후 | 3 | 기존 불일치 유지 | 변화 없이 일치 | 변화 없이 일치 |
| 앱1 삭제 성공 후 | 2 | 2 / 2지만 ID 집합 불일치 | 일치 | 일치 |

수정본은 별도 새 fixture로 강화 검증을 추가 실행했다. 생성 응답 ID가 예상 집합에 추가되고 삭제 ID가 제거되는지 DB 자체를 대조했다. DB의 `post BEFORE UPDATE` 실패 트리거로 댓글의 IDENTITY INSERT 이후 부모 개수 flush를 실패시켰으며, HTTP500 이후 댓글 INSERT와 부모 개수가 모두 롤백됐음을 확인했다. 트리거는 finally에서 제거했다. 이는 원래 DB가 아닌 임시 DB에서만 실행했다.

초기 `fixed.json`은 INSERT 자체 실패 주입이었다. 후속 `fixed-r2.json`이 INSERT 이후 실패·원자적 롤백과 예상 mutation 집합을 검증한 최종 근거다. 실패요청의 HTTP 응답과 API 조회의 HTTP200을 분리한다.

## 읽기 비용

수정본 JAR에서 동일한 댓글2개 fixture를 익명으로 조회했다. warm-up3회 이후20회 순차 요청을 보냈다. 쓰기나 다른 요청 부하는 없었다.

| 조건 | MySQL Com_select 증가 | 빈 측정 오버헤드 보정 후 |
|---|---:|---:|
| 댓글 캐시 OFF | 41 | 40 / 20요청 |
| 명시적 ON | 21 | 20 / 20요청 |

빈 probe3회는 모두 Com_select를1씩 증가시켰다. 관측된 추가 SELECT는 OFF에서 매번 수행하는 게시글+댓글 트리 조회와, ON에서 cached page의 좋아요 메타데이터를 새로 읽는 경로 차이에 부합한다. 이는 작은 fixture의 SQL 문장 수 관측이다. CTE의 행 탐색량, CPU·최대처리량·대규모 응답시간은 측정하지 않았다. 익명/회원·비어 있는 페이지/댓글 규모에 따라 비용은 다르다. **기존 대규모 캐시 성능 수치를 현재 기본 설정의 성과로 재사용하지 않는다.**

## 대안 비교

- 기존 캐시 유지: 현재5개 관측 지점에서 ID/개수가 달랐다. 선택한 최신성 기준 미달.
- TTL 단축: stale 시간을 줄여도 작성 응답 직후 새 읽기 일치를 보장하지 않는다. Replica·loader 경쟁도 별도다.
- Redis Pub/Sub 무효화: 커밋 후 전달 공백·subscriber 단절·메시지 유실·늦은 cache fill까지 계약과 검증이 늘어난다. 이번 최소 변경으로 선택하지 않았다.
- 모든 캐시 OFF: 대조 조건은 통과했지만 관련 없는 게시판·게시글 캐시까지 변경한다.
- 댓글 캐시만 OFF: 기존 DB 경로 재사용, 전달/재연결/로더 캐시 write-back을 추가하지 않는다. 읽기 DB 부하 증가를 감수하며 채택했다. 향후 실제 지원 부하에서 감당하기 어렵다는 근거가 생기면 계약을 명시하고 캐시 대안을 재검토한다.

## 검증과 산출물

- 캐시 설정 기본값/opt-in2건 + 기존 캐시24건, 합계26건 통과. 기본값에서 다른 두 캐시가 유지됨을 확인했다.
- 실제2JVM HTTP·DB 비교3조건, 수정본 강화 롤백 추가1회, 같은 JAR의20요청 SQL 비교2조건 및 빈 probe3회.
- 전체 앱 테스트나 대규모 부하 테스트는 실행하지 않았다. 변경 영향에 해당하는 테스트와 격리 통합 실험만 실행했다.
- 독립 읽기 전용 검수에서 'DB 자체의 기대 상태 검증', 'INSERT 이후 실패 주입', 'HTTP오류를 baseline stale로 오인하지 않기'를 반영했다.
- 실행 파일과 원본: `_workspace/cache-consistency/2026-09-22/run-01/`의 `compose.yml`, `isolated.properties`, `check.py`, `baseline.json`, `nocache.json`, `fixed-r2.json`, `*-read-cost.json`, `read-cost-calibration.json`, `unit-results.json`. 초기 스크립트는 `check-v1.py`에 보존했다. 이 폴더의 JAR는 로컬 증거이며 저장소에 게시하지 않는다.
- CI의 PR/배포 조건 변경은 이전 작업이다. 이번 변경을 원격에 push하거나 배포하지 않았다.

## 재현 시 주의

원래 compose가 아닌 전용 `compose.yml`로 실행한다. baseline은 `baseline.jar`를 `app.jar`로 복사한 뒤 사용한다. 전체 OFF 대조는 같은 baseline JAR에 `nocache.yml`을 합친다. 수정본은 기본 compose만 사용한다. `optin.yml`은 SQL 비교용이다. 파일 덮어쓰기를 막기 위해 새 실행 폴더 또는 `check.py --attempt N`을 사용한다. 실제 test DB는 tmpfs이므로 전용 compose 종료 시 삭제되며 원본 JSON은 보존된다.
