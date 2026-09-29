# 댓글 API 인증·페이지 입력 경계 — 2026-09-24

## 변경 결과

회원 댓글 DELETE에만 인증을 요구하고 비로그인은401로 거절한다. 동일 matcher를 인증 규칙과 entry point에 사용하며, 다른 보호 요청은 기존 `/login` 리다이렉트를 유지한다. 게스트 삭제는 기존 비밀번호 확인을 그대로 사용한다.

댓글 page는 서비스 진입부에서 `0 <= page <= Integer.MAX_VALUE / COMMENTS_SIZE`를 검증한다. 현재 페이지 크기100 기준 상한은21,474,836이다. 음수·곱셈 초과는400/VALIDATION_ERROR로 거절하고, `abc`, 소수, int 범위 초과 등 MVC 바인딩 실패도 같은 오류 종류로 처리한다. 응답에는 입력 원문을 반사하지 않는다. 정상 요청의 기존404·DB500은 유지한다.

## 재현과 설계 선택

첫 인증 기준선8개 중2개가 실패했다. 실제 프로덕션 SecurityFilterChain·MVC·CommentService를 연결한 비로그인 DELETE에서 JSON Accept는500, HTML Accept는 principal null 예외가 관측됐다. 인증된 테스트 사용자는 프로젝트의 UserPrincipal이며 `user(...)`로 설치했다. 실제 로그인 과정이나 JDBC 검증은 아니다.

단순히 인증만 추가하면 기존 formLogin이302를 반환한다. 현재 화면의 fetch는 리다이렉트 뒤 로그인 페이지200을 받을 수 있고 `response.ok`로 삭제 성공을 판단한다. 따라서 대상 DELETE는 리다이렉트 없이401을 반환한다. 본문은 비어 있으며 기존 화면은 실패 분기로 들어간다. 전체 API의 JSON 인증 응답을 새로 설계한 것은 아니다.

첫 수정의 `defaultAuthenticationEntryPointFor`는 관련14테스트를 통과했지만 독립 검토에서 기본 fallback 범위가 발견됐다. 대상이 아닌 회원 댓글 POST에 JSON Accept를 보낸 추가 테스트가 **기대302 / 실제401**로 실패했다. 최종 구현은 `DelegatingAuthenticationEntryPoint`의 fallback을 `LoginUrlAuthenticationEntryPoint("/login")`으로 명시한다. auth-final15개가 통과했으며 그중9개가 새 Security 경계 테스트다.

| 선택지 | 판단 |
|---|---|
| 서비스에 null 검사만 추가 | 직접 호출은 방어하지만 HTTP entry point 정책은 별도이므로 필터 체인을 유지하고 null 가드는 첫 DB 호출 앞에 보강 |
| 기존 formLogin302를 모든 요청에 사용 | 대상 fetch가 로그인200을 성공으로 오인할 여지가 있어 DELETE는401 |
| 모든 API 인증 응답을401로 변경 | 요청 범위를 넓히므로 제외. 나머지 보호 요청의 기존302를 테스트로 유지 |
| page<0만 검사 | 양수 곱셈 오버플로가 남으므로 기존 int offset 표현 상한도 검사 |
| 임의의 최대 페이지 수 설정 | 서비스 정책 근거가 없으므로 채택하지 않음 |
| 총 댓글 수 기준으로 CTE 조회 생략 | 과거 집계 불일치 시 댓글을 숨길 수 있어 보류 |

페이지 기준선에서는16개 중10개가 실패했다. 잘못된 정수가 모킹된 저장소까지 전달됐고 문자열/경로 타입 불일치는500이었다. `Integer.MIN_VALUE * 100`은0,21,474,837의 offset은 음수로 관측됐다. 저장소 mock이 빈 목록을 반환하므로 잘못된 정수의 기준선 응답은200이다. **실제 MySQL이 같은 요청에서 어떤 오류·응답을 반환하는지 재현한 결과가 아니다.**

## 검증 자료

원자료: [`_workspace/api-boundaries/2026-09-24/auth-page-v1`](../../_workspace/api-boundaries/2026-09-24/auth-page-v1/).

| 실행 | 테스트 | 실패 | 용도 |
|---|---:|---:|---|
| auth-baseline-v2 |8|2|변경 전 인증 경계 |
| auth-fixed |14|0|첫 수정, 이후 범위 누출 발견 |
| auth-scope-regression |9|1|다른 JSON API의302→401 회귀 |
| auth-final |15|0|명시적 fallback 수정 후 |
| page-baseline-v3 |16|10|정정된 테스트로 변경 전 페이지 경계 |
| page-fixed |57|0|페이지·인증·캐시 관련 회귀 |
| final-suite |256|0|최초 Claude 검수 전 전체 회귀 |
| final-suite-v2 |257|0|null principal 방어 보강 후 최종 전체 회귀 |

모두 errors/skipped0. `page-baseline-v2`는16개 중14실패였으나4개는 테스트가 DTO의 `currentPage` 대신 `page`를 단언한 작성 오류였다. 이를 고친 v3만 페이지 결함 기준선으로 사용하며 v2도 보존한다. `auth-baseline`, `page-baseline`은 sandbox Docker 접근 제한으로 리소스 생성 전 중단된 사전 시도다.

`verify-v2.py`와 `verification-v2.json`은 저장 XML·결과 JSON·선택 소스 스냅샷의 해시를 재대조한다. 최종 실행의 선택 소스는 현재 파일과 같다. 각9개 캠페인은 새 MySQL/Redis2개를 생성·정리했고 기존 실행 중10개 ID를 유지했다. 컨테이너를 띄운 사실이 새 테스트의 실제 JDBC 검증을 의미하지는 않는다.

새 페이지16테스트는 실제 MVC 바인딩·advice·서비스·Caffeine·sticky 필터를 사용하고 저장소/tx 콜백을 mock한다. 새 인증10테스트는 실제 프로덕션 Security 체인·MVC·서비스를 사용하지만 앱 전체 필터·세션 저장소·트랜잭션 프록시·DB를 검증하지 않는다. “저장소 호출 없음”은 실제 트랜잭션 시작의 모든 JDBC 통신 부재를 뜻하지 않는다.

## 검토 및 남은 범위

독립 Codex 검토에서 최초 인증 fallback 회귀를 찾았고, 재현·수정 후 추가 결함이 없다는 의견을 받았다. simplify 검토는 기존 framework delegate와 좁은 예외 handler 유지로 마쳤다. 실제 Claude 검수 결과는 별도 `_workspace/review/2026-09-24/api-boundaries-v1`에 보존한다.

- 큰 양수지만 표현 가능한 OFFSET의 성능 제한은 도입하지 않았다. 필요하면 별도 페이지 정책/커서 설계를 검토한다.
- MVC 오류 화면 HTTP200, 게시글 상세의 다중 인스턴스 캐시, 게스트 BCrypt 잠금 시간은 아직 별도 과제다.
- `loadComments()`의 오류 응답 처리 개선은 이번 API 경계 수정과 별개이며 화면 전체의 장애 처리를 해결했다고 주장하지 않는다.
- 운영 배포·커밋·푸시는 하지 않았다.

### Claude 검수 후 보강

실제 Opus5 high의 null principal 지적은 채택했다. 서비스 직접 호출 시 `NO_AUTHENTICATION`으로 첫 게시글 잠금/저장소 호출 전에 거절하며, 직접 호출 테스트를 추가했다. 트랜잭션 프록시 진입 전 거절을 뜻하지는 않는다.

빈401은 현재 `detail.html`의 삭제 실패 분기에서 JSON을 읽지 않으므로 화면 파싱 회귀가 아니다. API 공통 JSON 계약으로 전환하는 일은 별도다. ApiExceptionHandler와 MvcExceptionHandler는 각각 controller.api/controller.mvc로 대상이 분리되고 GlobalBindingConfig에는 예외 처리 메서드가 없다. 앱 전체 부팅 검증을 했다는 뜻은 아니다. Ant matcher의 마지막 세그먼트는 숫자로 한정되지 않아 비숫자 DELETE도 인증이 없으면401이 우선한다.
