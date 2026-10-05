# 악용 방지 — 좋아요 신원 결함과 요청 빈도 제한 (2026-10-05)

상태: **1절 구현 완료(브랜치 `fix/like-actor-identity`), 2절 다음 단계** — 결과 §5
배경: 일관성 기준(`consistency-contract-20261005.md`) B등급 공백 3 "요청 빈도 제한 없음".
Codex 검토 원문: `.claude/docs/research/rate-limit-review-codex-20261005.md` (코드 경로는 서브 에이전트가 파일로 재확인)

---

## 1. 먼저 고칠 결함 — 좋아요 신원을 클라이언트가 정한다

U2 측정 스크립트가 좋아요마다 아무 문자열이나 `guestIdentifier`로 보냈고, 그때마다 좋아요가 1씩 올랐다(400회 → 400 증가). 우연히 드러난 결함이다.

### 1-1. 무제한 좋아요 부풀리기 (확인됨)

```
클라이언트 JSON/STOMP 본문의 guestIdentifier
  → LikeService.resolveGuestIdentifier: extractUuid(raw).orElse(raw)
       서명 검증에 실패하면 원래 문자열을 그대로 씀
  → validateActor: 빈 값만 거름
  → upsertLike: (guest_identifier, 대상, 종류) 유일 → 문자열마다 새 행 → 좋아요 +1
```

- HTTP `/api/test/likes/add`(로그인 불필요)와 STOMP `/app/like/add` 둘 다 해당한다.
- STOMP에는 서명된 게스트 쿠키를 WebSocket 세션까지 전달하는 경로가 아예 없다. `GuestIdentifierFilter`는 검증한 UUID를 요청 속성에 두고, 핸드셰이크는 HttpSession 속성만 복사한다.

### 1-2. 남의 좋아요 취소 (확인됨)

`LikeRepository.deleteByActor`의 조건이 `user OR guestIdentifier`라서, 다른 게스트의 UUID를 알면 그 사람의 좋아요를 지울 수 있다.

### 1-3. 고치는 방법 (Codex 권고)

| 항목 | 변경 |
|---|---|
| HTTP | 본문의 `guestIdentifier`를 무시하고, 필터가 이미 검증해 둔 요청 속성(`guestUuid`)을 쓴다 |
| STOMP | 핸드셰이크 인터셉터가 원래 HTTP 요청의 `guestIdentifier` 쿠키를 검증해 WebSocket 세션 속성에 넣는다. 서비스는 메시지 본문이 아니라 세션에서 행위자를 꺼낸다 |
| 삭제 | 행위자를 하나로(회원 **또는** 검증된 게스트) 확정하고, 그 행위자의 행만 지운다 |
| 클라이언트 | `detail.html`이 보내는 `guestIdentifier`는 더 이상 쓰이지 않는다(무해, 정리 가능) |

**남는 한계:** 서명된 쿠키는 "서버가 발급한 신원"일 뿐 "한 사람"을 뜻하지 않는다. 필터는 쿠키가 없으면 새 쿠키를 발급한다. 그래서 쿠키를 지우고 다시 받으면 여전히 좋아요를 더 누를 수 있다 → 이건 2절의 빈도 제한이 막아야 할 몫이다. 지금 결함은 그 비용(요청 1번)을 0으로 만들고 있다.

---

## 2. 요청 빈도 제한

### 2-1. 위협 (이 서비스 기준)

| 순위 | 위협 | 영향 |
|---|---|---|
| 1 | 게스트 글·댓글 도배 | 높음 |
| 2 | 좋아요 조작 | 높음 (1절) |
| 3 | 목록·댓글·재조회 API 남용 | 높음 — 이미 병목인 Primary DB CPU를 직접 씀 |
| 4 | STOMP 메시지·연결 폭주 | 높음 — HTTP 제한으로는 못 막음 |
| 5 | 로그인·게스트 비밀번호 대입 | 성공 시 크지만 가능성 낮음. BCrypt 비용 소모 |

### 2-2. 어디에 둘까

| 위치 | 장점 | 단점 |
|---|---|---|
| **HAProxy stick-table (IP 기준)** | 가장 쌈. 앱·DB 도달 전에 막음. 두 앱 공통. Redis 무관 | IP 기준이라 NAT 오탐. WebSocket 업그레이드 뒤 메시지는 못 봄 |
| 앱 필터 + Bucket4j + Redis | 서버 간 공유 한도, 행위자 기준 가능 | 요청마다 Redis 왕복. Redis 장애 시 정책 필요(기존 기준: 빨리 실패) |
| 서버별 메모리(Caffeine) | 의존성 없음 | 서버 2대면 한도가 사실상 2배 |
| STOMP 인바운드 인터셉터 | 좋아요 메시지 단위로 막는 유일한 방법 | 재연결로 우회 → IP·행위자 기준도 필요 |

### 2-3. 권고 순서

1. **1절 결함 수정** (정합성 결함 — 빈도 제한으로는 못 고침: 초당 1회만 허용해도 신원을 바꾸면 무한히 쌓임)
2. **HAProxy stick-table로 쓰기 경로만 제한** (글·댓글 작성, 좋아요 HTTP) — 새 의존성 없음
3. STOMP·Bucket4j는 실제로 폭주가 보이면 그때

---

## 3. 검증 계획

| 단계 | 확인 |
|---|---|
| 1절 | 서명 없는 서로 다른 신원 N개로 좋아요 N번(두 서버) → 최대 1 증가. 남의 UUID로 삭제 시도 → 실패. 기존 동시성 테스트 유지 |
| 2절 | 알려진 한도로 M번 요청 → 허용·429 수가 예상과 일치, 거절된 요청은 앱·DB에 도달하지 않음(HAProxy 로그·DB 문장 수) |

---

## 4. 결정 요청

1. 1절(좋아요 신원 결함) 수정 진행 여부 — 추천: 진행
2. `/api/test/likes/*`: 기본 꺼짐 설정 뒤로 숨김(추천) / 삭제 / 유지 — ~~부하테스트 jmx는 이 경로를 쓰지 않음~~ **정정: 기준 부하테스트(`popping-load-test.jmx`, 좋아요 스레드 59개)가 이 경로를 씀.** 확인 때 검색 결과를 잘라 보아 놓쳤다(Codex 리뷰가 발견)
3. 2절 빈도 제한 첫 단계를 HAProxy stick-table로 — 추천: 1절 다음에

---

## 5. 결과 (2026-10-05)

결정: 1절 고침 / 테스트 API는 기본 꺼짐, **부하테스트 때만 켬** / 빈도 제한은 이 다음 HAProxy로.

| 변경 | 내용 |
|---|---|
| 신원 | 회원이면 회원, 아니면 `GuestIdentifierFilter`가 검증한 UUID만. HTTP는 요청 속성, STOMP는 `GuestIdentityHandshakeInterceptor`가 세션 속성으로 옮김. `LikeRequest`에서 필드 제거(옛 페이지가 보내도 무시) |
| 취소 | `deleteByActor`(회원 OR 게스트) → `deleteByUser` / `deleteByGuest` |
| 테스트 API | `LikeTestApiController`로 분리, `app.test-api.likes.enabled=true`일 때만 등록. 전송 로직은 `LikeBroadcaster`로 공용화 |
| 부하테스트 | `docs/load-test/compose.loadtest.yml`이 켜고, `run-ab.sh`가 이 파일을 겹쳐 띄운 뒤 GET이 405인지(켜짐) 확인하고 아니면 중단 |

| 검증 | 결과 |
|---|---|
| `LikeActorIdentityTest`(HTTP, 실제 필터) | 수정 전 코드에서 3건 모두 실패(5개 쌓임, 남의 좋아요 삭제, 위조 문자열 저장) → 수정 후 통과 |
| 실제 앱 2대 STOMP | 같은 쿠키·본문 값 5종 → 1개, 다른 서버에서도 1개, 남의 UUID로 취소 불가, 쿠키 없이 접속 → 새 신원으로 1개(빈도 제한 몫), 테스트 API 꺼짐 → 404 |
| 실행기 확인 절차 | 켬 405×4 / 끔 404×4 |

남은 일: 댓글 조회의 `?guestIdentifier=` 파라미터로 남의 반응 여부를 볼 수 있음(읽기만, 원래 있던 문제). 로컬 DB에 남은 서명 없는 게스트 좋아요 868행(이전 측정 스크립트가 만든 것으로 추정, 미정리).
원자료: `.claude/docs/research/like-identity-live-20261005/`, Codex 리뷰 `like-actor-identity-review-codex-20261005.md`, `like-test-api-gate-review-codex-20261005.md`.
