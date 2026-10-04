# U1 블로그용 앱 2대 실험 계획 (2026-10-04)

상태: **실행 완료 (2026-10-04)** — 결과는 §8
목적: 「서버마다 다른 게시글이 보였다」 후속 블로그 글에 넣을 증거(Grafana 캡처 + 결과 차트)를 실제 앱 2대에서 만든다.
대상 코드: `8621e0f` (U1). SPEC §7 실험(U4)의 **캐시 부분만** 앞당긴다. WebSocket·장애 실험은 이번 범위가 아니다.

---

## 1. 무엇을 증명하나

| 주장 | 증거 |
|---|---|
| local 모드: A에서 쓴 글이 B 목록에 바로 안 보인다 (B의 캐시가 남아 있음) | 시행별 stale 비율 ≈ 100%, 회복 시간 > 관찰 상한 |
| redis 모드: A에서 쓰면 B 캐시도 지워져 바로 보인다 | stale 비율 ≈ 0%, 회복 시간 수십~수백 ms |
| 실제로 Redis를 거쳐 전파됐다 | Grafana: `cache_invalidation_publish_total{result="sent"}`(쓴 쪽) ↔ `cache_invalidation_receive_total{result="evicted"}`(읽는 쪽) 증가가 시행 수와 맞음. local 구간은 0 |
| 실패는 없었다 | Grafana: `failed` / `dropped` / `invalid` = 0 |

**증명하지 않는 것:** Replica 지연 중 재적재(이번 실험은 일부러 Primary만 읽게 함), 부하 상황 성능, 메시지 유실 0 보장.

---

## 2. 실험 전에 발견한 것 — 게시판 목록에 정렬이 없다

`PostRepository.findPostListByBoard`의 JPQL에 `ORDER BY`가 없고, `getPostPage`도 정렬 없는 `PageRequest.of(page, 20)`를 쓴다.
게시판마다 글이 약 26만 건이라 **새 글이 첫 페이지에 나온다는 보장이 없다.** (최신 글이 첫 페이지에 안 보이는 제품 결함일 수 있음 → 이번엔 고치지 않고 백로그로 보고)

→ 그래서 기존 게시판 대신 **실험 전용 게시판**(`u1-exp`, 글 0건에서 시작)을 쓴다. 글이 20건 미만이면 정렬과 무관하게 전부 첫 페이지에 있다.

---

## 3. 구성

```
           ┌──────── popping-net (기존 docker 스택) ────────┐
host ──19091──▶ app-1 (U1 이미지) ─┐                       │
host ──19092──▶ app-2 (U1 이미지) ─┼─▶ mysql-master (읽기·쓰기 모두)
                                  └─▶ redis (세션 + 무효화 채널)
host ──8081/8082──▶ actuator ◀── Prometheus(15s) ◀── Grafana(+renderer)
```

| 항목 | 값 | 이유 |
|---|---|---|
| 이미지 | `./gradlew jibDockerBuild`로 로컬 빌드 (`popping-community:u1-8621e0f`) | 레지스트리 push 없음 |
| compose | 스크래치패드의 **override 파일**로 이미지·환경변수·포트만 교체. `docker-compose.yml`은 수정 안 함 | 사용자 결정 |
| 읽기 DB | `APP_DATASOURCE_READ_JDBC_URL` → **mysql-master** | Replica 지연 효과 분리 (SPEC §7) |
| 앱 포트 | `127.0.0.1:19091`, `19092` → 각 앱 9091 | HAProxy를 거치지 않고 쓰는 쪽·읽는 쪽 고정 |
| 모드 | 1회차 `APP_CACHE_INVALIDATION=local`, 2회차 `redis` (같은 이미지, 재기동만) | 전후 비교 |
| HAProxy | 띄우지 않음 | 불필요 |
| 상세 캐시 | 기본 OFF 유지 | 교란 제거 |

---

## 4. 시행 절차 (Python 표준 라이브러리 스크립트)

모드마다 **방향 2개(A→B, B→A) × 100회 = 200회**, 20회 블록으로 방향을 번갈아.

한 시행:
1. 읽는 쪽 `GET /boards/u1-exp` 2회 → 캐시 채움
2. 쓰는 쪽 `POST /boards/u1-exp/guest` (제목에 고유 토큰) → 302 확인
3. 즉시 읽는 쪽 `GET /boards/u1-exp` → 토큰이 있으면 fresh, 없으면 **stale**
4. stale이면 50ms 간격으로 최대 3초 재조회 → 보이기까지 걸린 시간 기록 (3초 안에 안 보이면 ">3s")
5. 쓰는 쪽에서 방금 글 삭제(비밀번호 확인 → 삭제) → 게시판을 20건 미만으로 유지

추가 1회(local 모드만): stale 상태에서 5분 넘게 기다려 **TTL 만료로만 회복됨**을 1건 기록.

산출: `trials.jsonl`(시행별 원자료), `summary.json`(모드×방향 stale 비율, 회복 시간 p50/p95/max).

---

## 5. 이미지(블로그용)

| # | 이미지 | 출처 |
|---|---|---|
| 1 | 모드별 stale 비율 막대 + 회복 시간 분포 | 결과 JSON → matplotlib PNG |
| 2 | Grafana: 인스턴스별 publish(sent) / receive(evicted, own) 증가량, local 구간과 redis 구간 나란히 | 임시 대시보드 → 렌더러 PNG → 대시보드 삭제 (기존 캡처 방식) |
| 3 | Grafana: failed / dropped / invalid = 0 | 같음 |
| 4 | Grafana: 두 앱 HTTP 요청률 (실험 트래픽이 실제로 양쪽에 갔음) | 같음 |
| 5 | (선택) 통합 테스트 리포트 / 구조도 | 기존 자료 |

---

## 6. 실행 순서

1. 실행 전 점검: Windows 커밋 여유(메모리 기록상 필수), 컨테이너 상태
2. 이미지 로컬 빌드
3. `u1-exp` 게시판 생성 (SQL insert, 기존 사용자 1명을 작성자로)
4. 스크립트 작성 → **Codex 리뷰(시행 독립성·판정 기준)** → 반영
5. monitoring 스택 기동, app-1/app-2 local 모드 기동 → 시행 → redis 모드 재기동 → 시행
6. Grafana 캡처, 차트 생성
7. 정리: app-1/app-2 정지(원래 상태), monitoring 정지(원래 상태), `u1-exp` 게시판과 실험 글 삭제
8. 블로그 원고 + 증거 폴더 작성, 전편 끝에 링크 한 줄

예상 시간: 1~2시간.

---

## 7. 결정 요청

| # | 질문 | 추천 |
|---|---|---|
| 1 | 이 계획으로 진행? | GO |
| 2 | 차트용 `matplotlib` 설치 (`pip install --user matplotlib`, 현재 미설치·uv 없음) | 설치. 거부 시 Grafana 이미지만 사용 |
| 3 | 실험 데이터: 로컬 `popping` DB에 `u1-exp` 게시판·글을 만들고 끝나면 삭제 | 생성 후 삭제 |
| 4 | 게시판 정렬 없음(§2) | 백로그 기록만, 이번엔 수정 안 함 |

---

## 8. 실행 결과 (2026-10-04 20:35~20:57 KST)

| 모드 | stale | 3초 내 회복 | 비고 |
|---|---|---|---|
| local | 200/200 | 0 | TTL 대기 1건: 301초 뒤 보임 |
| redis | 2/200 | 2 (96.8ms, 91.3ms) | 두 건 모두 각 방향의 **첫 시행**(index 0, 20) |

- Prometheus 최종값(redis): 각 앱 publish sent 300, receive own 300 / evicted 200 / no_cache 100 — 시행 수 기반 기대값과 정확히 일치. failed/dropped 0. 오류 0.
- 실행 중 바꾼 것: 읽기까지 Primary로 보내니 풀 260개 > max_connections 200 → 풀 10/10으로 축소 후 재기동. Grafana 3000번은 Windows 예약 범위(2938~3037)라 13000으로 override.
- 정리: app-1/app-2는 원래 compose로 재생성(Created, 미기동), monitoring 정지, `u1-exp` 게시판 삭제(잔여 글 0).
- 증거·블로그: `C:/Users/조용현/OneDrive/바탕 화면/Popping/cache-invalidation-20261004-v1/`, 원고 「다른 서버의 캐시는 지워지지 않았다 - Redis Pub Sub로 무효화 전파하기.md」.
- 백로그: 게시판 목록 쿼리에 ORDER BY 없음(§2).
