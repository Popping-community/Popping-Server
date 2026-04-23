# Popping-Server

주제: 게시판 만들기 (사용자가 많은 커뮤니티)

​	일반적인 게시판 기능 및 커뮤니티에서 지원하는 다양한 기능을 제공하는 커뮤니티(단, 데이터의 양이 많고, 트래픽이 많은 커뮤니티)

## Git Convention

#### type

- 하나의 커밋에 여러 타입이 존재하는 경우 상위 우선순위의 타입을 사용한다.
- fix: 버스 픽스
- feat: 새로운 기능 추가
- refactor: 리팩토링 (버그픽스나 기능추가없는 코드변화)
- docs: 문서만 변경
- style: 코드의 의미가 변경 안 되는 경우 (띄어쓰기, 포맷팅, 줄바꿈 등)
- test: 테스트코드 추가/수정
- chore: 빌드 테스트 업데이트, 패키지 매니저를 설정하는 경우 (프로덕션 코드 변경 X)

#### subject

- 제목은 50글자를 넘지 않도록 한다.
- 개조식 구문 사용
    - 중요하고 핵심적인 요소만 간추려서 (항목별로 나열하듯이) 표현
- 마지막에 특수문자를 넣지 않는다. (마침표, 느낌표, 물음표 등)

#### body (optional)

- 각 라인별로 balled list로 표시한다.
    - 예시) - AA
- 가능하면 한줄당 72자를 넘지 않도록 한다.
- 본문의 양에 구애받지 않고 최대한 상세히 작성
- “어떻게” 보다는 “무엇을" “왜” 변경했는지 설명한다.

## Additional Requirement

각 PR의 요구사항과 더불어, 해당 명세를 **반드시** 만족해야 합니다.

- 매 Step 마다 테스트 코드를 작성해보세요.
    - 커버리지 80% 이상을 맞추지 못할 경우, PR이 제한됩니다.
- Code Smell을 최소화 하세요.
    - SonarQube를 사용합니다. PR 과정에서 SonarQube Major Issue 발견 시, PR이 제한됩니다.
- Code Convention 을 사용하여 코드를 작성해 주세요.
- 여기서는 **네이버 핵 데이 컨벤션**을 사용합니다.
    - 출처: https://naver.github.io/hackday-conventions-java/
- 가능하면, 다른 분들의 PR도 코멘트를 달아보도록 노력해보세요. 상대방의 코드를 지적하는 것만이 코드 리뷰가 아닙니다. 코드를 보고 배울 점이 있다고 생각해도, 가감없이 코멘트를 달아주세요.

---

## 배포 및 모니터링 환경

Popping-community는 단일 EC2 인스턴스에서 Docker Compose 기반으로 운영된다.

```text
Client
  -> HTTPS 443
  -> Nginx TLS reverse proxy
  -> Spring Boot app container:8080
  -> MySQL container
```

### 배포 구조

- Spring Boot 애플리케이션과 MySQL은 같은 EC2 인스턴스에서 실행되며 CPU, memory, disk 자원을 공유한다.
- Nginx가 HTTPS 요청을 수신하고 TLS termination 후 Spring Boot `localhost:8080`으로 reverse proxy한다.
- GitHub Actions와 Jib를 사용해 Docker image를 빌드하고, EC2에서 Docker Compose로 배포한다.
- 민감 정보와 환경별 설정은 GitHub Secrets와 EC2 환경변수로 분리한다.

### 모니터링 구조

- Spring Boot actuator는 별도 management port에서 `health`와 `prometheus` endpoint를 제공한다.
- node-exporter로 EC2 host의 memory, load, disk, network, uptime을 수집한다.
- mysqld-exporter로 MySQL connection, query count, slow query, lock 관련 지표를 수집한다.
- PoppingOps가 주기적으로 actuator/exporter 지표를 수집해 서버 상태 snapshot을 만들고, WARN/CRITICAL/복구 알림을 Discord로 전송한다.
- 정기 장애 감지는 threshold 기반으로 처리하고, LLM은 Daily/Full Report와 사용자 분석 요청처럼 해석이 필요한 구간에만 사용한다.

운영 모니터링 봇은 [PoppingOps](https://github.com/Popping-community/popping-openclaw-ops-agent/)에서 관리한다.

---

## 성능 테스트 재현

### 테스트 환경

| 항목 | 사양 |
|------|------|
| 서버 | EC2 t2.micro (1 vCPU, 1GB RAM) — MySQL + Spring Boot 공유 |
| JVM | -Xms200m -Xmx240m |
| HikariCP | pool=30 |
| 부하 도구 | Apache JMeter 5.6.3 |
| 부하 기준 | 100 VUser, 평균 응답시간(avg) 기준 |
| 테스트 데이터 | 게시글 100만 건 · 댓글 1,000만 건 · 좋아요 450만 건 |

### JMeter 테스트 파일

테스트 스크립트는 [`docs/load-test/`](docs/load-test/) 에 있습니다.

| 파일 | 대상 시나리오 | 관련 개선 |
|------|-------------|----------|
| `popping-load-test.jmx` | 게시글 상세 조회 (댓글 포함) 100 VUser, 10분 | 댓글 첫 페이지 캐싱 |
| `popping-stampede-test.jmx` | evict 직후 동일 postId 50 동시 요청 (ramp 0s) | 캐시 스탬피드 방지 |
| `Like Concurrency Test.jmx` | 동일 사용자 좋아요 동시 요청 20 VUser (ramp 1s) | 좋아요 멱등 처리 |

### 실행 방법

1. [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi) 설치
2. 로컬 또는 EC2에서 서버 실행 (`./gradlew bootRun`, 포트 9091)
3. JMeter에서 `.jmx` 파일 열기 → 서버 주소·포트 확인 후 실행

### 주요 측정 결과

| 개선 항목 | 개선 전 | 개선 후 |
|----------|--------|--------|
| likes 풀스캔 제거 | 평균 10,460ms / 에러율 15.53% | 평균 167ms / 에러율 0% |
| 댓글 첫 페이지 캐싱 | 인기 게시글 152ms | 43ms |
| 캐시 스탬피드 방지 | CTE 10~17건 중복 실행 | CTE 1건 실행, 나머지 49건 캐시 히트 |
| 좋아요 멱등 처리 | 동시 요청 시 유니크 제약 예외 다수 발생 | 예외 없이 멱등하게 처리 |
