# Missing-post detail reads and pending view counts (2026-09-26)

`PostService.getPostResponse` previously called `ViewCountService.increaseView` before loading the post detail. A missing post or repository failure therefore left an increment in the in-memory pending map, even though the detail read failed. A later flush could attempt an UPDATE for a nonexistent post; its repository method returns `void`, so the flush path cannot distinguish zero affected rows.

## Reproduction and change

`PostViewCountAdmissionTest` connects the real `ViewCountService` to `PostService` with mock persistence. Before the change, four of six cases failed: missing post and storage failure each left pending count 1 with detail caching both disabled and enabled. Successful reads already passed.

The service now loads the post detail before queuing the increment. The successful response still merges the increment into the displayed view count. The new test verifies one repository lookup for a successful read, so the change does not add a second post lookup to this service path.

## Verification and limits

The six-class focused run passed: `PostViewCountAdmissionTest`, `PostServiceTest`, `PostCacheErrorMappingTest`, `PostDetailFreshnessTest`, `ViewCountServiceTest`, and `ViewCountMetricsTest`. All new six parameterized cases passed after the change. `git diff --check` found no whitespace errors. Repository calls, cache behavior, and pending counts in the new cases use local mocks/real in-memory services, not JDBC timing evidence. The DB cost conclusion is limited to the unchanged number of service-level `findById` calls.

Full suite (later the same day, disposable loopback MySQL/Redis, `_workspace/view-count-missing/2026-09-26/`): `full-suite-v1` ran 270 tests with 5 failures, all in `CommentDeleteConcurrencyTest` because the disposable DB user lacked `SELECT` on `performance_schema` (harness omission, not a code failure). `full-suite-v2` added that grant, as the 2026-09-24 runners did, and passed 270/270 with no failures, errors, or skips. Both runs removed only their own two containers and preserved the original ten. No two-JVM HTTP experiment was run for this change.

After a Codex review, two parameterized cases were added (8 total, all passing): two sequential reads display 6 then 7 and leave pending 2, with one `findById` when detail caching is enabled and two when disabled. The displayed count is exact only when no flush runs between the pending read and the response; a concurrent flush can make it momentarily lower, as before this change.

An opt-in cached detail can still be stale after another JVM deletes the post. This ordering change does not address that previously documented cache policy or Replica lag. The increment is still queued before reaction lookup, so a request that loads the post but then fails in reaction lookup still counts a view; this was also true before the change. It does not resolve the existing pending/flush durability limits. No deployment, commit, or push was performed.
