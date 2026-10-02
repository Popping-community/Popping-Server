# 2026-09-30 13:54 KST — stopped at D JIT gate and cleaned

- `campaign-f58c4833eee2` completed eligible one-off measurements for A, B and C,
  then stopped in `04-D-warmup`. D JIT growth was `2.2956%` at 1680s (fail) and
  `1.3242%` at 1800s (pass); the protocol required both to be `<=2%`.
- D measurement and all E traffic did not run. A/B/C each have only one eligible
  measurement, so every contrast remains unestablished and no future pooling is allowed.
- Supervisor PID `16748` exited with status `stopped`; cleanup completed. No owned
  live containers remain, and all original ten running container IDs are preserved.
- The user requested a memory save before opening a new IntelliJ terminal. No
  diagnostic or replacement campaign was started. Read
  `.claude/docs/handoff-change-load-20260930-jit-stop.md` next.

---

# 2026-09-30 10:15 KST — new campaign running

- Campaign root: `_workspace/change-load/2026-09-29/campaign-f58c4833eee2/`.
- Detached supervisor PID `16748`; `supervisor.json` status is `running`.
- Fresh seed validated: post `1,050,634`, comment `5,050,516`, likes `7,354,332`; seed DB stopped cleanly.
- Pre-start checks: IntelliJ OFF, physical RAM `7.06 GiB`, Windows commit headroom `27.02 GiB`, disk `93.96 GiB`, harness tests `27/27`.
- Monitoring started on loopback ports `7670/7671`. `01-A` smoke passed with
  `368` parent rows, `38` redirect children and zero errors.
- The real anchored preflight passed at exactly smoke `end_epoch + 15s`:
  positive HTTP rate `8.2206/s`, mean `15.95ms`, p95 `41.90ms`, p99
  `123.98ms`; both jobs were UP and current CPU/DB/JIT queries were finite.
- `01-A` 30-minute warmup started at `10:17:43 KST`. No JIT gate or
  measurement result exists yet.
- Do not restart or pool results from `d238ee50af5c`. Source/JAR reuse remains anchored to `f856516f7666`.

---

# 변경별 부하검증 v2 — 중단 확인 / 새 세션 실행 대기

2026-09-30 후속 확인: 아래07:46 기록은 과거 스냅샷이다.

- campaign-d238ee50af5c는08:34:07 KST에02-B 모니터링 사전 검사 실패로 중단.
- A의30분 예열 JIT28분1.3940%,30분0.9677% 통과. 본측정900s 부모436,809건,
  오류0. 자동 판정 유효1회/STEADY. 마지막120초499.5167TPS, p99=396ms.
  B~E 유효0회이므로 모든 변경별 비교 미성립. 원자료/PNG 별도 검수 미완료.
- B smoke 부모373건/오류0. 마지막 응답 약49초 뒤 요청률0, 지연mean/p95/p99
  NaN. 앱/리소스up=1, CPU/DB/JIT 유한값. 오류는 B 예열 이전에 발생했다.
- 09:05 이후 실제 프로세스 조회에서 감독/JMeter 없음. Docker 조회에서 해당
  캠페인의 컨테이너/볼륨/네트워크 없음. 기존10개 동일ID running 유지.
- 미래 실행용preflight_v2.py 수정: HTTP 질의만 성공한smoke end_epoch+15s로
  고정, 현재up/CPU/DB/JIT 확인 유지, 요청률>0 요구. 완료되지 않았거나180s
  초과된smoke는 거부. 과거 캠페인 안의 파일은 수정하지 않음.
- 회귀 최초5개: 수정 전1error/2subtest failures. 시간 경계/잘못된 기록 검증을
  보강한 회귀7개를 포함하여 최종27개 통과. 독립 Codex 검토에서 차단 결함 없음;
  주석 보장 범위를 좁히고 대기 중 만료/미래 시각 테스트 권고를 반영했다.
  HTTP 응답/시간을 모킹한 단위검증이며 실제 Prometheus 재실행 검증은 아님.
  end_epoch는 마지막 HTTP 완료가 아닌 JMeter 프로세스 종료 시각이다. 종료가
  지연되거나 수집이 누락되면 여전히 실패로 중단하며, 모든 타이밍을 보장하지 않는다.
- 사용자 결정: IntelliJ를 닫고 새 세션에서 새 캠페인을 진행한다.
  이번 세션에서는 새 캠페인 생성/seed복원/부하실행 없음.

---

## 과거 실행 중 기록

2026-09-30 07:46 KST 확인. [사전 프로토콜](change-load-20260930-v2-plan.md).
현재 첫 A 버전의 30분 워밍업이 실행 중이다. 본 측정과 변경별 성능 비교는
아직 완료되지 않았다. 이 문서는 이 시점의 스냅샷이며 이후 상태는 raw의
`state.json`, `supervisor.json`, 각 `run.json`, `measurement-summary.json`을 확인한다.

- 캠페인: `_workspace/change-load/2026-09-29/campaign-d238ee50af5c/`.
- 이전 실패 캠페인 `campaign-f856516f7666`은 보존. 소스/JAR 해시와 이미지 ID를
  검증한 A~E 빌드만 재사용했고 새 네트워크·볼륨·입력·DB seed를 만들었다.
- 원본 10개 running ID가 이전 기록과 일치. IntelliJ OFF 확인.
  준비 시 여유 RAM 약7.8GiB, 실제 Windows 커밋 여유 약30.0GiB,
  디스크 약94.8GiB. 실행 중 물리 RAM과 커밋 여유를 별도로 감시한다.
- seed 복원 및 CSV 대상 검증 완료: 게시글1,050,634 / 댓글5,050,516 /
  반응7,354,332. 복원 중 redo checkpoint 및 background histogram lock 경고는
  있었지만 import 정상 종료와 clean shutdown, 대상 검증을 통과했다.
- harness20개 검사 통과, 1800초 합성 입력의 시간 구간·표본 수 검증 통과.
  감독 스크립트의 성공/실패 cleanup 호출 및 3회 요건·JIT 제외 집계 검사 통과.
  Java 비즈니스 코드는 수정하지 않았고 기존 Java 전체 회귀를 반복하지 않았다.
- 첫 A smoke30s: 부모373 / 저장 redirect child38 / 오류0 / 손상 행0.
- 첫 A 실제 모니터링 preflight 통과: Prometheus app/resource UP,
  latency/throughput/CPU/DB/JIT 모든 필수 질의가 유한값을 반환했고
  Grafana dashboard UID를 확인했다. 이전 CRLF 결함 수정의 실제 수집 경로 확인.
  이번 PNG는 아직 렌더링·시각 검수 전이다.
- A warmup 시작07:45:40 KST. 1680/1800초 JIT 두 gate <=2% 요구,
  실패 시 캠페인 중단. 통과 시900초 본 측정. 과거 실패를 통과로 바꾸지 않는다.

실행 감독 스크립트는 선언한15slot 순서를 수행한다. 자정/04시 전75분 여유가
없으면 앱 없이 대기하고, 완료 또는 실패 시 exact ID/label이 맞는 실험 자원만
정리한다. 결과 분석은 JIT까지 통과한 유효 회차로만 집계한다. 각 비교 양쪽3회가
없으면 비교 미성립이다. 마지막 자동 집계에도 자원·이미지의 별도 검토가 필요하다.

실제 Grafana 캡처는 각 slot 앱 제거 후 수행한다. 해당 블로그 수정은 유효한
비교 및 이미지 검토 뒤 진행할 후속 작업이다. 현재 글 수정·commit·push·배포 없음.
