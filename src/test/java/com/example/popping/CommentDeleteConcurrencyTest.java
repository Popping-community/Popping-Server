package com.example.popping;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.*;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.*;
import com.example.popping.service.CommentService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real service calls against the guarded disposable MySQL fixture only. */
@SpringBootTest
@EnabledIfSystemProperty(named = "portfolio.test.jdbc.url",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/portfolio_regression\\?.*")
class CommentDeleteConcurrencyTest {
    @Autowired CommentService comments;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired BoardRepository boards;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(Long userId, Long postId) { }

    private Fixture fixture() {
        String suffix = Long.toString(System.nanoTime());
        User user = users.saveAndFlush(User.create("cd-" + suffix, "synthetic-hash", "cd-" + suffix, UserRole.USER));
        Board board = boards.saveAndFlush(Board.create("board", "test", "cd-" + suffix, user));
        Post post = posts.saveAndFlush(Post.createMemberPost("test", "test", user, board));
        return new Fixture(user.getId(), post.getId());
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getUserId()).thenReturn(userId);
        return principal;
    }

    private Long member(Fixture f, Long parent) {
        return comments.createMemberComment(f.postId(), new MemberCommentCreateRequest("member"), principal(f.userId()), parent);
    }

    private Long guest(Fixture f, Long parent) {
        return comments.createGuestComment(f.postId(), new GuestCommentCreateRequest("guest", "guest", "test-password"), parent);
    }

    private void assertCounts(Fixture f, int expected) {
        int stored = posts.findById(f.postId()).orElseThrow().getCommentCount();
        long actual = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, f.postId());
        assertAll(() -> assertEquals(expected, stored, "post.comment_count"),
                () -> assertEquals((long) expected, actual, "actual comment rows"));
    }

    private void awaitBlockedWriters(Long postId, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'post'
                      AND l.INDEX_NAME = 'PRIMARY' AND l.LOCK_DATA = ?
                    """, Integer.class, postId.toString());
            if (blocked != null && blocked >= count) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }
        fail("Expected " + count + " real MySQL post-lock waiters for " + postId);
    }

    /** Observe both actual service transactions waiting on the same post lock. */
    private List<Throwable> whilePostLocked(Fixture f, List<Callable<Void>> jobs) throws Exception {
        var pool = Executors.newFixedThreadPool(jobs.size());
        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                posts.findForUpdateById(f.postId()).orElseThrow();
                for (var job : jobs) {
                    futures.add(pool.submit(() -> {
                        try {
                            job.call();
                            return null;
                        } catch (Throwable error) {
                            return error;
                        }
                    }));
                    awaitBlockedWriters(f.postId(), futures.size());
                }
            });
            List<Throwable> errors = new ArrayList<>();
            for (var future : futures) {
                Throwable error = future.get(25, TimeUnit.SECONDS);
                if (error != null) errors.add(error);
            }
            return errors;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    /** Hold the first write uncommitted until the second writer is waiting. No FIFO assumption. */
    private List<Throwable> afterFirstWriteBeforeCommit(Fixture f, List<Callable<Void>> jobs) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> second = new TransactionTemplate(transactionManager).execute(status -> {
                try {
                    jobs.getFirst().call();
                } catch (Exception error) {
                    throw new IllegalStateException("First service write failed", error);
                }
                Future<Throwable> waiting = pool.submit(() -> {
                    try {
                        jobs.get(1).call();
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                });
                awaitBlockedWriters(f.postId(), 1);
                return waiting;
            });
            Throwable error = second.get(25, TimeUnit.SECONDS);
            return error == null ? List.of() : List.of(error);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentMemberAndGuestDeletes_keepCount() throws Exception {
        Fixture f = fixture();
        Long first = member(f, null);
        Long second = guest(f, null);
        List<Throwable> errors = whilePostLocked(f, List.of(
                () -> { comments.deleteComment(f.postId(), first, principal(f.userId())); return null; },
                () -> { comments.deleteCommentAsGuest(f.postId(), second, "test-password"); return null; }));
        assertTrue(errors.isEmpty(), errors.toString());
        assertCounts(f, 0);
    }

    @Test
    void queuedCreateThenDelete_keepCount() throws Exception {
        Fixture f = fixture();
        Long removed = member(f, null);
        List<Throwable> errors = afterFirstWriteBeforeCommit(f, List.of(
                () -> { member(f, null); return null; },
                () -> { comments.deleteComment(f.postId(), removed, principal(f.userId())); return null; }));
        assertTrue(errors.isEmpty(), errors.toString());
        assertCounts(f, 1);
    }

    @Test
    void sameCommentDeletedTwice_oneNotFoundWithoutSecondDecrement() throws Exception {
        Fixture f = fixture();
        Long removed = member(f, null);
        Callable<Void> delete = () -> { comments.deleteComment(f.postId(), removed, principal(f.userId())); return null; };
        List<Throwable> errors = whilePostLocked(f, List.of(delete, delete));
        assertEquals(1, errors.size(), errors.toString());
        CustomAppException error = assertInstanceOf(CustomAppException.class, errors.getFirst());
        assertEquals(ErrorType.COMMENT_NOT_FOUND, error.getErrorType());
        assertCounts(f, 0);
    }

    @Test
    void deletingParent_removesDescendantsAndTheirCount() {
        Fixture f = fixture();
        Long root = member(f, null);
        Long child = guest(f, root);
        member(f, child);
        member(f, null);
        comments.deleteComment(f.postId(), root, principal(f.userId()));
        assertCounts(f, 1);
    }

    @Test
    void queuedReplyThenParentDelete_removesCommittedReply() throws Exception {
        Fixture f = fixture();
        Long root = member(f, null);
        List<Throwable> errors = afterFirstWriteBeforeCommit(f, List.of(
                () -> { member(f, root); return null; },
                () -> { comments.deleteComment(f.postId(), root, principal(f.userId())); return null; }));
        assertTrue(errors.isEmpty(), errors.toString());
        assertCounts(f, 0);
    }

    @Test
    void rollback_restoresSubtreeAndCountAndAllowsNextWrite() {
        Fixture f = fixture();
        Long root = member(f, null);
        guest(f, root);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            comments.deleteComment(f.postId(), root, principal(f.userId()));
            throw new IllegalStateException("Synthetic rollback after delete");
        }));
        assertCounts(f, 2);
        member(f, null);
        assertCounts(f, 3);
    }

    @Test
    void wrongGuestPassword_doesNotChangeRowsOrCount() {
        Fixture f = fixture();
        Long root = guest(f, null);
        member(f, root);
        CustomAppException error = assertThrows(CustomAppException.class,
                () -> comments.deleteCommentAsGuest(f.postId(), root, "wrong"));
        assertEquals(ErrorType.ACCESS_DENIED, error.getErrorType());
        assertCounts(f, 2);
    }

    @Test
    void queuedParentDeleteThenReply_rejectsReplyWithoutOrphans() throws Exception {
        Fixture f = fixture();
        Long root = member(f, null);
        List<Throwable> errors = afterFirstWriteBeforeCommit(f, List.of(
                () -> { comments.deleteComment(f.postId(), root, principal(f.userId())); return null; },
                () -> { member(f, root); return null; }));
        assertEquals(1, errors.size(), errors.toString());
        CustomAppException error = assertInstanceOf(CustomAppException.class, errors.getFirst());
        assertEquals(ErrorType.COMMENT_NOT_FOUND, error.getErrorType());
        assertCounts(f, 0);
    }

    @Test
    void anotherPostsParent_isRejectedForMemberAndGuest() {
        Fixture first = fixture();
        Fixture second = fixture();
        Long root = member(first, null);
        assertEquals(ErrorType.COMMENT_NOT_FOUND, assertThrows(CustomAppException.class,
                () -> member(second, root)).getErrorType());
        assertEquals(ErrorType.COMMENT_NOT_FOUND, assertThrows(CustomAppException.class,
                () -> guest(second, root)).getErrorType());
        assertCounts(first, 1);
        assertCounts(second, 0);
    }

    @Test
    void anotherPostsComment_cannotBeDeletedThroughWrongPost() {
        Fixture first = fixture();
        Fixture second = fixture();
        Long member = member(first, null);
        Long guest = guest(first, null);
        assertEquals(ErrorType.COMMENT_NOT_FOUND, assertThrows(CustomAppException.class,
                () -> comments.deleteComment(second.postId(), member, principal(first.userId()))).getErrorType());
        assertEquals(ErrorType.COMMENT_NOT_FOUND, assertThrows(CustomAppException.class,
                () -> comments.deleteCommentAsGuest(second.postId(), guest, "test-password")).getErrorType());
        assertCounts(first, 2);
        assertCounts(second, 0);
    }

    @Test
    void wrongMember_doesNotChangeSubtreeOrCount() {
        Fixture owner = fixture();
        Fixture stranger = fixture();
        Long root = member(owner, null);
        guest(owner, root);
        assertEquals(ErrorType.ACCESS_DENIED, assertThrows(CustomAppException.class,
                () -> comments.deleteComment(owner.postId(), root, principal(stranger.userId()))).getErrorType());
        assertCounts(owner, 2);
    }

    @Test
    void guestParentDeletion_countsAllDescendants() {
        Fixture f = fixture();
        Long root = guest(f, null);
        Long child = member(f, root);
        guest(f, child);
        comments.deleteCommentAsGuest(f.postId(), root, "test-password");
        assertCounts(f, 0);
    }

    @Test
    void legacyCrossPostTree_isRejectedWithoutDeletingEitherPost() {
        Fixture first = fixture();
        Fixture second = fixture();
        Long root = member(first, null);
        Long foreignChild = member(second, null);
        // Deliberately introduce invalid historical data without using the service.
        jdbc.update("UPDATE comment SET parent_id = ? WHERE id = ?", root, foreignChild);
        assertThrows(IllegalStateException.class,
                () -> comments.deleteComment(first.postId(), root, principal(first.userId())));
        assertCounts(first, 1);
        assertCounts(second, 1);
    }

    @Test
    void historicalUndercount_isRejectedRatherThanClamped() {
        Fixture f = fixture();
        Long root = member(f, null);
        member(f, root);
        jdbc.update("UPDATE post SET comment_count = 1 WHERE id = ?", f.postId());
        assertThrows(IllegalStateException.class,
                () -> comments.deleteComment(f.postId(), root, principal(f.userId())));
        assertEquals(1, posts.findById(f.postId()).orElseThrow().getCommentCount());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, f.postId()));
    }
}
