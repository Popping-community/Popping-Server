# 작성 직후 댓글 캐시 우회 + 짧은 TTL — 2026-09-22

## 구현

`CommentService.getFirstPageCommon`에서 `StickyPrimaryHolder.isSticky()`이면 캐시를 조회하거나 갱신하지 않고 기존 트랜잭션 안에서 댓글 트리를 구성한다. 기존 라우팅이 해당 읽기를 Primary로 보낸다. 방금 읽은 트리의 좋아요 값을 사용하므로 중복 좋아요 집계 조회도 하지 않는다. DB 오류를 오래된 캐시 성공 응답으로 바꾸지 않는다.

일반 요청은 기존 `cache.get(postId, Callable)`로 공통 페이지를 적재한다. 개인 반응 합성은 이후에 수행한다. 댓글 캐시는 기본 ON이다. TTL은 `app.cache.comment-first-page.ttl-seconds`로 분리했고 기본 5초, 0 이하는 시작 시 오류로 처리한다. 게시판 5분·게시글 상세 30분은 그대로다. `app.cache.comment-first-page.enabled=false`의 DB 직접 조회 경로도 남긴다.

sticky는 게시글별 표시가 아니라 사용자 요청의 쿠키다. 유효 시간 동안 해당 사용자가 조회하는 **다른 게시글의 댓글 첫 페이지도** 캐시를 우회한다.

## 실환경 검증

격리 MySQL Primary/Replica와 앱 두 대, 쿠키 저장소를 사용했다. 최초 후보 JAR는 캐시를 명시적으로 ON으로 켜고 검증했다. 이후 기본 ON을 반영한 최종 JAR에서는 캐시 관련 property override를 모두 제거하고 같은 검증을 다시 수행했다. 두 실행 모두 다른 앱의 stale 페이지를 먼저 적재했다. 실제 운영 환경·HAProxy·회원 로그인 세션 검증은 아니다.

- 생성 6회, 삭제 2회: 성공 쿠키로 다른 앱을 조회하면 바뀐 ID/개수 반환. 해당 GET 8건의 CTE는 Primary에서 각 1회.
- 같은 앱을 바로 조회한 일반 독자 8건: 기존 stale 페이지 유지, 양 DB에서 CTE 0회. 우회가 공통 캐시를 덮어쓰지 않음을 확인.
- 복제 적용 정지 상태에서 쿠키와 TTL 만료 후 조회 2회: Replica의 이전 상태를 다시 읽음. TTL만으로 DB 최신성을 보장하지 못함.
- 복제 동기화와 TTL 만료 후 조회 2회: 일반 독자도 새 ID/개수 확인.
- 부모 UPDATE 오류 주입 2회: INSERT → UPDATE → ROLLBACK 순서 확인, ID와 부모 카운터 전후 동일, HTTP 500 및 성공 쿠키 미발급.
- 입력 검증 실패 2회: HTTP 400 및 성공 쿠키 미발급.

최초 후보와 최종 기본 설정에서 각각 16개 케이스가 통과했고, 각 HTTP GET 20건의 실제 쿼리 경로를 순차 요청의 시간창·게시글 ID로 대조했다. 관련 테스트 39개와 격리 DB를 사용하는 전체 테스트 175개가 통과했다(실패·skip 0). 기본 ON 복원 후에도 전체 175개를 다시 실행해 통과했다. 기존 원래 DB를 대상으로 테스트하지 않았다.

## 남아 있는 의미상의 한계

현재 쿠키는 3초 동안 유효하다. 만료 뒤에는 일반 조회 정책으로 돌아가므로, Replica 지연 또는 아직 유효한 다른 앱의 캐시 때문에 방금 본 댓글이 다시 안 보일 수 있다. TTL 5초는 캐시 항목 수명이고 커밋 기준 최신성 상한이 아니다. **Replica가 이미 따라잡았더라도 쿠키가 먼저 만료되고 캐시가 남아 있는 구간은 별도로 주의해야 한다.** 지속적인 read-your-writes/단조 읽기를 구현했다고 표현하지 않는다.

최종 JAR에서 이 경계도 실제로 재현했다. 앱2의 빈 캐시를 적재한 뒤 앱1에서 작성하면 작성자는 앱2에서 새 댓글을 읽었다. Replica 동기화까지 확인한 다음 쿠키 만료 후 다시 조회하자 빈 캐시가 반환됐고, 캐시 만료 후에는 새 댓글이 반환됐다. `cookie-ttl-boundary.json`에 응답·쿠키·Replica ID를 보존했다. 즉각적인 작성 후 조회를 개선했지만 이 후속 UX 문제는 남는다. 지속적인 작성자 최신성이 필요하면 별도 후속 설계가 필요하며, 현재 TTL만 조정해 해결했다고 주장하지 않는다.

또한 이 변경은 댓글 API 범위다. 게시글 상세·게시판 캐시의 모든 필드 최신성, 전체 화면 정합성, 복제 장애 복구를 해결한 결과가 아니다. 쿠키를 전달하지 않는 클라이언트에는 작성자 우회도 적용되지 않는다.

## 비용 확인

사전 조건과 결과는 `_workspace/cache-consistency/2026-09-22/run-03-hybrid/cost-plan.md`, `cost-results.json`에 기록했다. 앱과 각 DB는 CPU 1개·메모리 512MiB 한도이며, 쓰기 없는 일반 게스트 조회를 50req/s로 예약했다. 분포별 고정 워밍업 20초·측정 30초를 앱1/앱2/앱1 순으로 반복했다. 6구간 **9,000건 모두 ID/개수가 일치하고 HTTP 오류가 없었다.**

다음은 실행별 지표의 중앙값이며 여러 구간의 응답을 합친 p95가 아니다.

| 합성 분포 | 요청 실행 후 p95 | 예약 시점부터 응답까지 p95 | 생성기 대기 p95 | 1,500건 완료 시간 | Replica CPU | CTE 수(3회) |
|---|---:|---:|---:|---:|---:|---|
| 댓글 10,000개 글 1개 집중 | 45.48ms | 83.04ms | 25.30ms | 29.99초 | 6.34% | 6 / 6 / 6 |
| 댓글 100개 글 100개 분산 | 110.45ms | 265.59ms | 83.02ms | 29.99초 | 6.66% | 500 / 500 / 500 |

모든 구간에서 완료 시간 ≤33초·생성기 대기 p95 ≤1초라는 사전 실험 기준을 충족했다. 새 키에 대한 별도 20개 동시 요청도 CTE **1회**, 응답 ID/개수 일치를 확인했다. 작성자 우회 요청에는 이 병합이 적용되지 않는다.

이전 OFF 집중 조회는 같은 합성 분포에서 1,500건 완료에 70.29~72.40초, Replica CPU 99.58~99.74%가 필요했다. 이번 후보에서는 해당 부하 누적이 나타나지 않았다. 반면 긴 TTL의 warm 구간 CTE 0회와 비교하면 5초 만료에 따른 재조회 비용은 증가했다. 일반 독자의 오래된 캐시 보유 시간을 줄이는 대신 이 비용을 감수한다. **5초가 최적 TTL이라고 입증한 실험은 아니며**, 다른 TTL이나 쓰기 혼합 부하를 비교하지 않았다.

CTE 계수는 performance_schema digest 증분이다. 집중 구간 rows examined는 각 60,600, 분산은 100,000 / 99,800 / 100,000으로 원본 그대로 남겼다. 분산 두 번째 구간의 차이 원인은 확인하지 못했으며 보정하지 않았다. 좋아요 등 다른 쿼리는 CTE 집계에 포함되지 않고, CPU는 Replica 전체 cgroup 값이다.

긴 TTL/OFF 자료와는 별도 시점 실행이다. 고정 워밍업은 정상상태를 입증하지 않으며, 공유 호스트·기존 컨테이너·로컬 생성기의 영향을 배제하지 못한다. 운영 개선율·최대 처리량·통계적 유의성·회원 개인화 성능으로 일반화하지 않는다.

## 원본 자료

`_workspace/cache-consistency/2026-09-22/run-03-hybrid/`:

- `run.py`, `support.py`, `compose.yml`, `isolated.properties`: 격리 재현 환경과 검증.
- `correctness-01.json`, `route-*-01.jsonl`, `route-audit-01.json`: 응답, 쿠키, 실제 DB 경로와 롤백 증거.
- `final.jar`, `final.properties`, `final.yml`, `runtime-final.json`, `correctness-02.json`, `route-*-02.jsonl`, `route-audit-02.json`: property override 없는 최종 기본 설정 검증.
- `unit-results.json`, `full-suite-results-initial.json`, `full-suite.gradle`, `test.properties`: 테스트 결과와 격리 DB 설정.
- `full-suite-results-final.json`: 기본 ON 복원 후 전체 175개 테스트 결과.
- `cookie-ttl-boundary.json`: Replica가 따라잡은 상태에서도 쿠키/캐시 만료 차이로 이전 페이지가 다시 보이는 경계 재현.
- `cost-plan.md`, `cost.py`, `cost_support.py`, `cost-results.json`, `cost-samples-*.json`: 합성 비용 관측 절차와 실행별 원본. 비용 측정은 최초 후보 JAR의 명시적 ON 설정에서 수행했다. 최종 JAR에서는 기본값만 ON으로 바뀌었으며 비용 측정을 다시 합산하지 않았다.
- `cost-preflight-failure.json`: Docker 권한 부족으로 측정 시작 전에 실패한 실행. 데이터 측정 실패로 합치지 않는다.
- `validation.json`: 소스·JAR·원본 파일 해시와 검증 요약.
- `containers-before-cleanup.json`, `remaining-containers.txt`: 소유 label 확인 후 임시 앱/DB 4개와 전용 네트워크 제거. 원래 Popping 컨테이너 10개는 계속 실행 중임을 확인했다.

배포·push·병합은 하지 않았다. main 변경이 원격 배포로 이어질 수 있으므로 로컬 검증 결과와 배포 완료를 구분한다.
