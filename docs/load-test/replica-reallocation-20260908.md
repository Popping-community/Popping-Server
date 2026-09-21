# MySQL CPU 재배분 비교 — 2026-09-08

## 결론

400 VUser의 동일한 B 구성에서 DB 총 CPU를 2개로 유지한 채 Primary/Replica를 `1/1`에서 `0.5/1.5`로 바꿨다. 처리량과 응답 시간, 복제 지연은 개선됐지만 복제 안정성 기준에는 여전히 미달했다. 따라서 `0.5/1.5`를 운영 권장값으로 확정하지 않는다.

| 항목 | 1/1 기준선 | 0.5/1.5 재배분 | 변화 |
|---|---:|---:|---:|
| tail 성공 TPS | 442.94 | 446.78 | +0.87% |
| tail 평균 | 20.88 ms | 14.49 ms | -30.6% |
| tail p95 | 68 ms | 46 ms | -32.4% |
| 오류 | 0 | 0 | 동일 |
| lag 최대 / p95 / 종료 | 31 / 20 / 31초 | 23 / 17 / 23초 | 개선 |
| lag 기울기 | +2.68초/분 | +2.84초/분 | 악화 |
| 측정 후 GTID 회복 | 61.83초 | 77.32초 | 악화 |
| Replica tail CPU | 94.79% / 1 CPU | 117.48% / 1.5 CPU | 할당량 대비 약 78.3% |
| Replica throttled period | 79.35% | 34.51% | 개선 |
| Primary tail CPU | 23.81% / 1 CPU | 23.32% / 0.5 CPU | 할당량 대비 약 46.6% |
| Primary throttled period | 0% | 3.44% | 새로 관측 |

복제 판정은 마지막 5분의 lag p95 2초 이하, 최대 5초 이하, 종료 2초 이하, 절대 기울기 0.2초/분 이하를 요구한다. 재배분 결과는 최대 23초, 종료 23초, 기울기 +2.84초/분이어서 `UNSTABLE`이다. lag 평균은 6.8초에서 3.05초로 낮아졌지만 측정 후 완전 회복은 오히려 15.49초 길어졌다.

## 조건과 검증

- B 구성, 400 VUser, 요청 비율·fixture·앱 이미지·앱 CPU/메모리·Hikari write/read pool 20/30을 유지했다.
- warmup 600초 1회, GTID 및 idle 안정화, measure 900초, 측정 후 GTID 회복 순서로 실행했다.
- 측정 오류 0, TPS `STEADY`, JIT 및 관측 coverage를 통과했다.
- Primary와 Replica의 최종 행 수가 일치하고 복제 thread가 ON, 오류 0, GTID catch-up을 확인했다.
- 종료 시 임시 컨테이너를 모두 제거했다. 기존 서비스가 복구됐고 두 DB 모두 정확히 1 CPU/1 GiB로 원복됐다.

이 비교는 Primary와 Replica 할당을 동시에 바꿨으므로 Replica 증설만의 독립 효과를 분리하지 못한다. 다만 총 DB CPU가 같은 상황에서 `0.5/1.5`가 `1/1`보다 400 VUser의 사용자 응답과 lag 크기를 줄였다는 근거는 된다.

## 다음 실험

CPU를 Replica에 더 넘기기보다 MySQL Replica의 병렬 적용 설정과 실제 적용 worker 활용도를 먼저 확인한다. Primary는 0.5 CPU에서 throttling이 생기기 시작했고, Replica에 1.5 CPU를 줘도 lag가 계속 증가했기 때문이다. 설정 진단 후 같은 400 VUser를 한 번만 재측정하는 편이 원인을 더 잘 구분한다.

원본은 `.tmp/replica-reallocation-05-15/campaigns/replica-20260908-reallocation-01/`과 `.tmp/replica-reallocation-05-15/runs/replica-20260908-reallocation-01-80-measure/`에 보존했다.
