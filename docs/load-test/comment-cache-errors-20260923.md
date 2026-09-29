# 댓글 캐시와 오류 응답 계약 (2026-09-23)

## 관측과 수정

실제 CacheConfig의 Caffeine, CommentService, CommentController, StickyPrimaryFilter, ApiExceptionHandler를 연결한 standalone MockMvc로 재현했다. PostService/저장소와 TransactionTemplate callback은 mock이다. 실제 DB 오류나 전체 보안 체인을 재현했다고 해석하면 안 된다.

수정 전 신규 12개 테스트 중 3개가 실패했다. 없는 게시글의 댓글 첫 페이지를 캐시 loader에서 조회하면 `CustomAppException(POST_NOT_FOUND)`가 `Cache.ValueRetrievalException`으로 감싸졌고, API의 포괄 예외 처리기가 이를500으로 처리했다. sticky 쿠키, 캐시 비활성, 두 번째 페이지는404였다.

| 없는 게시글 조회 | 수정 전 | 수정 후 |
|---|---|---|
| 캐시 활성, 첫 페이지 miss | 500 / INTERNAL_ERROR | 404 / POST_NOT_FOUND |
| sticky 쿠키 | 404 / POST_NOT_FOUND | 동일 |
| 댓글 첫 페이지 캐시 비활성 | 404 / POST_NOT_FOUND | 동일 |
| 두 번째 페이지 | 404 / POST_NOT_FOUND | 동일 |

Spring의 `Cache.get(key, Callable)`는 loader 예외를 ValueRetrievalException으로 감싼다. [Spring6.2.3 Cache API](https://docs.spring.io/spring-framework/docs/6.2.3/javadoc-api/org/springframework/cache/Cache.html).

## 선택한 방식

| 대안 | 영향 | 결정 |
|---|---|---|
| 전역 advice에서 캐시 예외 처리 | 다른 API와 캐시의 오류 계약도 바뀜. 서비스 호출자에게는 wrapper가 남음 | 이번 범위에는 과함 |
| RuntimeException 전체 또는 원인 체인을 재귀적으로 해제 | 내부 IllegalArgumentException이400으로 바뀌거나 세부 메시지가 노출될 수 있음 | 제외 |
| 댓글 캐시 경계에서 직접 원인인 CustomAppException만 전달 | 기존 도메인 오류 객체·코드·메시지를 보존. 알려지지 않은 오류는 기존 wrapper 유지 | 채택 |

`CommentService#getFirstPageCommon`의 cache.get 호출만 try/catch한다. 즉시 원인이 CustomAppException이면 같은 객체를 던지고, 그 외에는 원래 ValueRetrievalException을 던진다. 캐시 만료 시간, hit/miss 흐름, sticky 우회, 전역 advice는 변경하지 않았다. 기존 CacheTests의 mock도 실제 계약에 맞는 wrapper를 던지도록 수정했다.

## 검증

신규 테스트12개는4경로의404 계약,4경로의 DB 계열 오류500/정보 비노출, 캐시 내부 IllegalArgumentException500 유지, 임의 wrapper 속 도메인 예외의 재귀 해제 방지, 실패→성공→hit, 도메인 예외 객체 보존을 다룬다. sticky 필터의 요청 후 ThreadLocal 정리도 확인한다.

- baseline:12 tests /3 failures. 실행 당시 소스·XML·HTML·로그 보존.
- fixed-targeted:32 tests /0 failures/errors/skipped. 신규12개와 기존 캐시 테스트 포함.
- 최종 전체 회귀216 tests /0 failures/errors/skipped. 결과와 XML 대조: `_workspace/cache-errors/2026-09-23/mapping-v1/final-suite/result.json`, `verification.json`.
- 모든 Gradle 실행에 `--rerun-tasks` 적용. 실행 전후 선택 소스 SHA-256 동일성 확인.
- 격리 MySQL/Redis는 전체 회귀 실행용 의존성이다. 신규12개가 실제 DB를 사용했다는 뜻은 아니다. 각 campaign의 고유 label/ID가 일치한 컨테이너2개만 정리하고 기존10개 ID 보존 여부를 기록한다.

독립 Codex 검수에서 blocker가 없었다. Claude 사용 한도로 이번 수정의 Claude 재검수는 미완료다. 별도 캐시 추상화를 만들지 않는 것으로 simplify 검토를 마쳤다.

## 한계와 다음 후보

모든 예외의 경로별 응답을 같게 만드는 작업이 아니다. 기존 캐시 loader 내부 IllegalArgumentException은500을 유지한다. 임의 wrapper에 도메인 예외가 포함됐다는 이유만으로 이를4xx로 바꾸지 않는다. 이미 캐시에 들어 있는 오래된 정상 응답의 무효화 문제도 별개다.

전체 JVM·DB·네트워크를 통한 실제 HTTP 오류 주입, 성능·장애율 개선 측정, 운영 배포는 수행하지 않았다. PostService의 다른 cache.get 호출은 같은 유형의 후속 점검 후보이며 이번 수정에는 포함하지 않았다. 앞선 검수의 CleanupGuard 정리 실패 기록 보완도 남아 있다.
