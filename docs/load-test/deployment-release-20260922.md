# 서로 다른 불변 이미지의 로컬 교체 검증

검증일: 2026-09-22. 후보 투입과 기존 요청 완료 후 종료를 따로 검증한 데 이어,
**서로 다른 이미지에서 기대한 버전이 실행되는지 확인한 뒤 교체**하는 절차를
격리 환경에서 검증했다. 이번 변경은 실험 드라이버와 증거 문서다. 운영 Java,
기존 Docker Compose 및 CI 배포 경로는 수정하지 않았다.

## 무엇이 실제로 달라졌나

이전 실험은 같은 JAR를 앱 두 대에 마운트했다. 이번에는 JAR를 이미지 안에
포함하고 Compose가 정확한 `sha256:` 이미지 ID를 사용하도록 했다. JAR 마운트는
없다. 실행 중인 정확한 컨테이너에서 `/release/app.jar`를 추출해 준비한 파일과
SHA-256을 대조했다. 이미지 이름이나 프록시 서버 이름만으로 버전을 판정하지 않았다.

| 버전 | 실제 이미지 ID 앞부분 | JAR SHA-256 앞부분 |
|---|---|---|
| V1 | `0e5c2d6e1afc` | `6d9a22ca762b` |
| V2 | `32964a8a452c` | `7c4b121382b4` |

전체 해시는 원본 `images.json`, `artifacts.json`, `embedded-artifact-audit.json`에
있다. JAR entry별 비교 결과 차이는 `BOOT-INF/classes/templates/board/form.html`
한 개다. 보호 페이지에 포함된 meta marker를 V1/V2로 바꿨으며 클래스와 라이브러리
바이트는 같다. **실제 다른 이미지·JAR이지만 기능·스키마 변경 없는 합성 릴리스**다.
기능 변경 호환성이나 DB migration 안전성을 증명한 것은 아니다.

## 실행 순서와 중단 조건

기존 도구 `admit_candidate.py`와 `quiesce_server.py`를 실험 드라이버에서 연결했다.

1. 기존 V1은 UP, 후보 V2는 MAINT로 시작한다. 이미지 ID와 프록시 대상 IP를 대조한다.
2. 후보의 readiness, 기존 앱에서 발급한 Redis 세션을 사용한 보호 GET,
   기대한 버전 marker를 확인한다. HTTP 200만으로 통과시키지 않는다.
3. 투입된 후보가 실제 프록시 경로에서 보호 본문·V2 marker·candidate backend를
   반환하는지 확인한다. 이 확인 전에는 기존 앱을 제어하지 않는다.
4. 기존 앱을 MAINT로 전환하고 세션·큐가 비면 정확한 컨테이너에 SIGTERM을 보낸다.
5. 기존 앱 종료 후 같은 세션으로 V2 보호 페이지를 읽고 댓글을 한 번 작성한다.

게이트의 `admitted=null`은 거절 성공으로 취급하지 않는다. 투입 이후 프록시
V2 확인이 실패하고 아직 기존 앱을 건드리지 않았다면 후보를 MAINT로 되돌리고
기존 앱 UP을 확인한다. 이 복구의 실패 주입은 이번 세 시나리오에 포함하지 않았다.
기존 앱 제어가 시작된 뒤 실패하면 자동 rollback하지 않고 작업을 중단한다.

## 결과

| 시나리오 | 구별한 원인 | 주기적 보호 GET | 기존 앱 |
|---|---|---:|---|
| readiness-503 | 실제 V2·인증 GET 200·버전 일치, readiness만 503 | 29/29 정상, 전부 V1 | 이미지·ID·시작 시각 동일, UP·running 유지 |
| wrong-release | readiness 200/UP·인증 GET 200, V3 기대값에 실제 V2가 불일치 | 34/34 정상, 전부 V1 | 이미지·ID·시작 시각 동일, UP·running 유지 |
| replace-v2 | readiness·V2 일치 후 프록시에서 보호 본문·V2 확인 | 33/33 정상, V1 11건·V2 22건 | SIGTERM 종료, code 143, OOM false |

주기적 관측은 총 **96건**이며 모두 HTTP 200과 기대한 보호 본문이 확인됐다.
샘플러는 요청 후 0.2초를 대기한다. 응답 시간을 포함하므로 정확한 5req/s
개방형 부하 생성기는 아니며, 이 결과는 저부하 기능 검증이다.

정상 교체에서는 기존 앱 제어 전 프록시 탐색 GET 3건 중 후보의 실제 V2 응답을
확인했다. 기존 앱 MAINT 확인 이후 시작한 주기적 GET은 전부 V2·candidate였다.
기존 앱 종료 후 별도로 보낸 보호 GET **12건**도 전부 V2·candidate·200·본문 일치를
만족했다. 12건과 탐색 3건은 앞의 96건에 포함하지 않았다.

이어 댓글 POST를 자동 재전송 없이 한 번 보냈다. candidate 응답은 200, ID=1이며
DB의 해당 게시글 댓글 행은 ID=1 한 개, 부모 comment_count=1이었다. JSON 댓글
응답에는 HTML 버전 marker가 없다. 해당 쓰기는 후보 backend와 실행 이미지 ID,
직전·직후 보호 페이지 검증을 근거로 판정한다. 여기서는 직후 주기적 보호 GET도
성공했으며 후보의 ID·시작 시각·이미지가 시나리오 동안 유지됐음을 대조했다.

## 재생성과 주소 처리

readiness 실패 주입을 제거하면서 후보를 재생성했다. 새 컨테이너 ID와 동일한
V2 이미지 ID를 확인했고, 후보를 MAINT에 둔 채 HAProxy 주소를 실제 IP로 설정한
후 다시 대조했다. **이번에는 재생성 전후 IP가 `172.25.0.5`로 같았다.**
따라서 다른 IP로 바뀌는 경우나 HAProxy DNS resolver 동작까지 검증했다고
표현하지 않는다. 그 시나리오는 별도 주입이 필요하다.

## 검증·정리

- 배포 도구 단위 테스트 16개를 이번 실행에서 다시 통과했다.
- 실행 이미지에서 추출한 두 JAR의 해시와 entry별 차이를 대조했다.
- 독립 검토의 미확인 상태 구분, 샘플러 종료 확인, 실행 JAR 해시 대조를 반영했다.
- 운영 Java와 클래스 바이트 변경이 없으므로 전체 Java 테스트를 다시 실행하지
  않았다. 이전 단계의 175개 통과 결과를 이번 실행 결과로 합산하지 않는다.
- 실험 컨테이너 5개를 정리하고 기존 Popping 컨테이너 10개의 ID가 계속 실행 중임을
  확인했다. 임시 SESSION 쿠키를 삭제했다. 만든 이미지 두 개는 재현용으로 남겼다.
- 외부 서버 배포, 이미지 push, commit은 수행하지 않았다.

## 남은 범위

이는 후보 거절 시 기존 릴리스 유지와 정상 후보 교체를 증명한다. 다음은
교체 후 새 버전이 실패했을 때 보존한 V1 이미지로 복귀하는 별도 시나리오다.
이번에는 자동 rollback, 기능·스키마·세션 직렬화 호환성, 전환 중 쓰기 무손실,
고부하 용량, 호스트 장애 HA를 증명하지 않았다. 이전의 실제 행 잠금 POST 드레인
증거도 별도 실험이며 이번 이미지 교체 실험에서 재실행한 것은 아니다.

## 원본과 재현 진입점

`_workspace/deploy-safety/2026-09-22/release-v1/`

- `plan.md`, `review.md`: 사전 조건과 독립 검토
- `prepare.py`: 합성 V1/V2 JAR 생성 및 변경 entry 제한
- `rig.py`: setup → audit → check → cleanup. 기존 결과가 있으면 새 디렉터리·project·port·tag로 버전을 구분해야 한다.
- `artifacts.json`, `images.json`, `embedded-artifact-audit.json`: JAR·이미지·실행 컨테이너 연결
- `scenario-readiness-503.json`, `scenario-wrong-release.json`, `scenario-replace-v2.json`
- `gate-*.json`, `quiesce-replace-v2.json`, `candidate-recreated.json`
- `old.log`, `candidate.log`, `proxy.log`, `db-final.json`, `cleanup.json`, `manifest.json`

기존 단계: [후보 투입](deployment-admission-20260922.md), [요청 완료 후 종료](deployment-drain-20260922.md).
