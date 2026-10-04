# P1′ U1 캐시 무효화 전파 — 설계 설명과 Codex 리뷰 반영안 (2026-10-03)

상태: **구현 완료 (2026-10-04, 8621e0f)** — 결정 A: 반영안 승인, 결정 B: B1
상위 SPEC: `docs/spec/p1-prime-cluster-local-state-20261002.md` §6 U1
Codex 리뷰 원문: `.claude/docs/research/p1-prime-u1-review-codex-20261003.md`

결정이 필요한 것은 두 가지다. → [§7 결정 요청](#7-결정-요청)

---

## 1. 무엇을 고치나

앱 서버 A·B는 각자 JVM 메모리(Caffeine)에 캐시를 따로 들고 있다.

```
지금
  사용자 ──글쓰기──▶ A : DB 커밋 → A 캐시 evict ✅
  다른 사용자 ──목록──▶ B : B 캐시엔 예전 목록이 그대로 ❌ (TTL 5분까지)

U1 이후
  사용자 ──글쓰기──▶ A : DB 커밋 → A 캐시 evict → Redis 채널에 "boardFirstPage/3 지워" 발행
                                                        │
  B (채널 구독 중) ◀───────────────────────────────────┘ → B 캐시 evict ✅
```

| 캐시 | 키 | TTL | 전파 효과 |
|---|---|---|---|
| `boardFirstPage` | `boardId` | 5분 | 큼 (최대 5분 → 거의 즉시) |
| `commentFirstPage` | `postId` | 5초 | 작음 |
| `postDetail` | `postId` | 30분 | 기본 OFF라 지금은 없음 |

### 고치지 못하는 것 (중요)

evict 뒤 다음 조회는 캐시를 **Replica에서** 다시 채운다.
Replica가 아직 그 쓰기를 반영하지 못했으면 **지우자마자 예전 값이 다시 채워진다.**
전파는 "남의 서버가 들고 있던 낡은 값"만 없앤다. Replica 지연은 이번 범위에서 재현·기록만 한다(SPEC §8).

---

## 2. 전체 구조

```
[쓰기 요청 스레드]
 PostService / CommentService
   └─ publishEvent(CacheEvictEvent(cacheName, Long key))       ← 트랜잭션 안
                    │
                    ▼  커밋 성공 시에만 (AFTER_COMMIT)
 CacheEvictListener
   ├─ ① 로컬 캐시 있으면 evict
   └─ ② broadcaster.broadcast(cacheName, key)    ← 로컬 캐시가 없어도 항상
            │
            ├─ local 모드: NoOpCacheInvalidationBroadcaster (아무것도 안 함)
            └─ redis 모드: RedisCacheInvalidationBroadcaster
                   └─ 큐(1000)에 넣고 즉시 반환 ──▶ [발행 스레드 1개] ──▶ Redis PUBLISH

[다른 인스턴스]
 RedisMessageListenerContainer ──▶ CacheInvalidationSubscriber
   ├─ 내가 보낸 것(origin == 내 InstanceId)? → 무시
   ├─ 그 캐시가 나한테 없음? → 무시 (no_cache)
   └─ cache.evict(key)
```

설정 하나로 켜고 끈다: `app.cache.invalidation=local|redis` (기본 `local`).
롤백 = `local`로 되돌리기. 세션 저장소(`app.session.store`)와 독립.

---

## 3. 이미 작성한 코드 (미커밋 WIP) — 왜 이렇게 했나

| 파일 | 역할 | 설계 이유 |
|---|---|---|
| `event/CacheEvictEvent` | 키 타입 `Object` → `Long` | JSON 왕복하면 `1L`이 `Integer 1`로 돌아온다. `Long(1).equals(Integer(1))`은 **false**라 원격 evict가 에러 없이 조용히 빗나간다. 타입으로 아예 막았다 |
| `cache/CacheInvalidationMessage` | 전송 형식 `{cacheName, key, origin}` | `origin`으로 자기 메시지를 걸러낸다 |
| `cache/InstanceId` | JVM마다 UUID 1개 | 재시작하면 바뀌어도 된다. "이 프로세스가 보낸 것"만 알아보면 되므로 |
| `cache/RedisCacheInvalidationBroadcaster` | 큐 + 발행 스레드 1개 | 아래 §3.1 |
| `cache/CacheInvalidationSubscriber` | 수신 → 로컬 evict | 결과를 `evicted / own / no_cache / invalid`로 지표화 |
| `cache/NoOpCacheInvalidationBroadcaster` | local 모드 | 단일 인스턴스엔 알릴 상대가 없음 |

### 3.1 왜 요청 스레드에서 바로 Redis에 보내지 않나

```
바로 보내면:  쓰기 요청 ── convertAndSend() ── (Redis 멈춤) ── 타임아웃까지 대기 ── 응답
             → 사용자 글쓰기가 Redis 때문에 느려지거나 실패  ❌ AC4 위반

큐 방식:      쓰기 요청 ── queue.offer() ── 즉시 응답
             발행 스레드 ── convertAndSend() ── (실패해도 로그·지표만)
```

- 큐가 가득 차면(1000건) **버린다.** 결과는 "Pub/Sub 메시지 유실"과 같다 → 상대 서버는 TTL까지 낡은 값. 지표 `dropped`로 보인다.
- 스레드가 1개라 이 서버가 보낸 메시지 순서가 유지된다.
- Codex: 더 단순한 대안 없음. 동기 전송은 타임아웃을 짧게 해도 요청을 막는다.

---

## 4. 앞으로 작성할 코드

### 4.1 `CacheEvictListener` — 로컬 evict와 발행 분리

```java
// 지금: 로컬 캐시가 없으면 return → 발행도 안 됨 (버그)
if (cache == null || event.key() == null) return;
cache.evict(event.key());

// 변경 후
if (event.key() == null) return;
Cache cache = cacheManager.getCache(event.cacheName());
if (cache != null) {
    cache.evict(event.key());          // ① 로컬
}
broadcaster.broadcast(event.cacheName(), event.key());   // ② 항상
```

왜 "항상"인가: A에선 `postDetail`이 꺼져 있어도 B에선 켜져 있을 수 있다. A의 사정으로 B에게 알리지 않으면 안 된다.

### 4.2 설정 클래스 (신규, 예: `config/app/CacheInvalidationConfig`)

| 빈 | local | redis |
|---|---|---|
| `InstanceId` | ✅ | ✅ |
| `NoOpCacheInvalidationBroadcaster` | ✅ | — |
| `RedisCacheInvalidationBroadcaster` | — | ✅ |
| `CacheInvalidationSubscriber` | — | ✅ |
| `RedisMessageListenerContainer` (채널 구독) | — | ✅ |

> **사실 정정:** "local 모드면 Redis 빈이 하나도 없다"는 틀렸다.
> `spring-boot-starter-data-redis`가 항상 의존성에 있어서 `RedisConnectionFactory`·`StringRedisTemplate`은
> 모드와 상관없이 만들어진다(연결은 처음 쓸 때 맺음). 테스트는 **위 표의 무효화 전용 빈**이 없는지만 본다.

### 4.3 Codex 리뷰로 고칠 것

| 심각도 | 위치 | 문제 | 조치 |
|---|---|---|---|
| **Blocker** | `CacheEvictListenerTest:56` | 키가 `Long`인데 `"free"` 문자열 → **지금 컴파일 안 됨** | `Long` 키로 수정 |
| Major | `CacheEvictListener:20` | 로컬 캐시 없으면 발행까지 건너뜀 | §4.1 |
| Major | 테스트 계획 | "local이면 Redis 빈 없음"은 사실 아님 | §4.2 기준으로 좁힘 |
| Minor | `CacheInvalidationSubscriber:54` | 본문이 JSON `null`이면 `readValue`가 null 반환 → 다음 줄 NPE | null 체크 |
| Minor | `RedisCacheInvalidationBroadcaster.destroy()` | 종료 2초 넘겨도 큐를 계속 비움 (`awaitTermination` 결과 무시) | 시간 초과 시 `shutdownNow()` |
| Minor | `RedisCacheInvalidationBroadcaster.broadcast()` | "절대 예외를 던지지 않는다" 약속인데 두 예외만 잡음 | 바깥을 `RuntimeException`으로 감쌈 |
| Minor | `PostDetailFreshnessTest:66` | 리스너 생성자 인자 증가로 컴파일 깨짐 | NoOp 발행기 주입 |

선택적 미세 개선(Codex): JSON 직렬화를 발행 스레드로 옮기기, 수신 시 `String` 변환 없이 `byte[]`로 바로 읽기.

---

## 5. 테스트 계획 (Codex 우선순위 순)

| # | 테스트 | 검증하는 것 |
|---|---|---|
| 1 | 컴파일 수정 | 빌드 가능 |
| 2 | **실제 트랜잭션 연결** 커밋/롤백/트랜잭션 없음 | AC5: 롤백이면 발행 안 함. `fallbackExecution=true`라 트랜잭션 없이 호출하면 즉시 실행 |
| 3 | 로컬 캐시 없어도 발행 + local/redis 빈 구성 | §4.1, §4.2 |
| 4 | 발행 스레드가 막혀도 호출자는 즉시 반환 + 큐 가득 차면 drop | AC4 |
| 5 | JSON: `1L` 왕복이 `Long`, null/깨진 본문, 모르는 캐시, 자기 origin | 수신 측 방어 |
| 6 | Redis 재시작 후 재구독 (실제 Redis, `@EnabledIfSystemProperty`로 게이트) | §6 미검증 항목 확인 |

### #2를 꼭 이렇게 해야 하는 이유

`@TransactionalEventListener(AFTER_COMMIT)`은 Spring이 **트랜잭션 동기화에 콜백을 걸어 두었다가 커밋 성공 시에만** 실행한다.
테스트에서 `listener.onCacheEvict(...)`를 직접 부르면 그 장치를 통째로 건너뛰므로 롤백 여부를 검증하지 못한다.

→ DB 없이 동작하는 가짜 트랜잭션 매니저(`AbstractPlatformTransactionManager` 상속) + 실제 이벤트 리스너 등록으로
`publishEvent` → 커밋하면 발행 / 롤백하면 미발행을 확인한다. Mockito 목 트랜잭션 매니저로는 동기화가 돌지 않아 안 된다.

---

## 6. 범위 밖 발견 · 미검증

- **ViewCountService 기존 결함 (백로그):** 조회수 flush 때 상세 캐시를 직접 evict하는데, DB 쓰기에 실패해 다시 큐로 돌아간 항목까지 같이 지운다. 상세 캐시가 기본 OFF라 당장 영향 없음. 나중에 이벤트로 옮길 땐 "성공한 항목만, 각 트랜잭션 커밋 후" 발행해야 한다.
- **미검증:** "Redis가 재시작되면 `RedisMessageListenerContainer`가 알아서 재구독한다"는 Codex 주장이다(소스 직접 확인 안 함). 테스트 #6과 SPEC §8 실험으로 확인한다.

---

## 7. 결정 요청

### 결정 A — Codex 반영안으로 U1 진행?

§4.3의 7건 전부 반영, ViewCountService 결함은 백로그 기록만. **추천: 이대로 GO.**

### 결정 B — `redis` 모드인데 기동 시 Redis가 없으면?

먼저 상황 정리:
- 세션을 Redis에 두는 운영 구성(`app.session.store=redis`)이면 Redis 없이는 **로그인 자체가 안 된다.** 이때 캐시 전파만 살려서 기동하는 건 의미가 작다.
- 세션은 로컬인데 캐시 전파만 `redis`인 구성에서는 의미가 있다.

| 선택지 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **B1. 기동 실패 유지** (추천) | Spring 기본. 리스너 컨테이너가 Redis 연결 못 하면 기동 실패 | 설정과 실제 동작이 항상 같다. 추가 코드 없음. "전파가 꺼진 줄 모르고 운영"하는 사고가 없다 | Redis 장애 중에는 새 인스턴스를 못 띄운다 (재배포·스케일아웃 불가) |
| B2. 경고 후 local처럼 기동 | 연결 실패 시 경고 로그 + 지표, NoOp으로 동작 | Redis가 죽어도 앱은 뜬다 | 조용히 전파가 꺼진 채로 운영될 수 있다. 나중에 Redis가 살아나도 구독을 다시 붙이는 로직이 필요 → 범위 증가 |
| B3. 기동은 하고 구독은 백그라운드 재시도 | 컨테이너가 뜬 뒤 계속 재연결 시도 | 가용성과 정합성 사이 균형 | Spring 설정 세부 확인 필요(미검증), 테스트 비용 큼 |

**이미 실행 중일 때** Redis가 죽는 경우는 선택과 무관하다: 발행은 실패해도 요청은 성공(AC4), 재시작 후 재구독(§6 확인 예정).
SPEC §8은 "Redis 없이 redis 모드로 기동"을 **관찰·기록만** 하기로 했으므로, B1으로 두고 실험 결과를 보고 다시 정하는 것이 범위상 가장 안전하다.

---

## 8. 진행 순서 (승인 후)

1. WIP stash → main fast-forward (`62cc9e5` 이후 main은 병합 커밋+문서뿐, `src/` 변경 없음 확인) → stash pop
2. §4.3 수정 + 리스너 분리 + 설정 클래스
3. 테스트 작성·실행 (`JAVA_HOME=jdk-21`, `--no-daemon --max-workers=1`)
4. 구현분 Codex 리뷰 → 반영 → 커밋 (`mysql/`, 미추적 부하 기록 문서는 제외, 파일 지정 add)

---

## 9. 구현 중 확인한 사실 (2026-10-04)

- **B1 전제 실측:** spring-data-redis 3.4.3에서 도달 불가능한 Redis(localhost:6390)로 `RedisMessageListenerContainer.start()`를 호출하면
  523ms 후 `RedisListenerExecutionFailedException(RedisConnectionFailureException: Unable to connect to Redis)`을 던진다.
  `SmartLifecycle.start()` 예외는 컨텍스트 기동 실패로 이어지므로 B1은 추가 코드 없이 기본 동작으로 성립한다. (일회용 프로브 테스트로 확인 후 삭제)
- **재구독 실측 (§6 미검증 항목 해소):** 임시 Redis 7.4(127.0.0.1:16391)에서 `CacheInvalidationRedisIntegrationTest` 2건 통과.
  원격 evict 0.25초, pub/sub 연결을 `CLIENT KILL`로 끊은 뒤 재구독·수신까지 테스트 2.8초. 끊긴 동안 발행된 메시지는 유실된다(테스트가 재발행으로 확인).
- **포트 함정:** Windows 예약 포트 범위(6380~6579 등) 때문에 6391 바인딩 실패 → 16391 사용. `-D`는 테스트 JVM에 전달되지 않으므로 init 스크립트로 `portfolio.test.redis.port`를 넘긴다.


## 10. 검토한 대안: Redis 공유 캐시 (2026-10-04)

Redis 공유 캐시도 대안이었다. 복사본이 하나뿐이라 서버 간 전파 지연이나 메시지 유실이 없고, 정합성만 보면 더 단순하다. 대신 조회할 때마다 Redis 왕복과 목록 역직렬화가 붙는다. 또 지금은 Caffeine이 JVM 안에서 막아 주는 동시 미스의 중복 로딩을 서버 사이에서 다시 막아야 한다. 이번에는 기존 조회 경로를 그대로 두는 쪽을 골랐다. 하지만 그 비용을 측정하지 않았으므로, 이 선택이 공유 캐시보다 낫다고 말할 근거는 없다. 이번 실험이 보여 준 것은 전파가 전파 없음보다 낫다는 것까지다. 두 방식 모두 지연된 Replica에서 캐시가 다시 채워지는 문제는 해결하지 못한다. 같은 앱 2대 구성에서 게시판 첫 페이지의 p99 지연과 요청당 CPU를 두 방식으로 번갈아 비교하고, 공유 캐시의 비용이 작으면 전환을 검토할 생각이다.

Codex 검토 원문: `.claude/docs/research/u1-pubsub-vs-redis-cache-codex-20261004.md` (L1+L2 2단 캐시·짧은 TTL만 쓰는 안도 이 규모엔 비추천). 비교 실험은 백로그.
