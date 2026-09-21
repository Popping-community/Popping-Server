package com.example.popping;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.*;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.repository.*;
import com.example.popping.service.CommentService;
import com.example.popping.service.PostService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only through the disposable-MySQL runner, with its guarded JDBC URL. */
@SpringBootTest
@EnabledIfSystemProperty(named = "portfolio.test.jdbc.url",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/portfolio_regression\\?.*")
class CommentWriteConcurrencyTest {
    @Autowired CommentService comments;
    @Autowired PostService posts;
    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired UserRepository users;
    @Autowired BoardRepository boards;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private record Fixture(Long userId, Long postId) { }

    private Fixture fixture() {
        String suffix = Long.toString(System.nanoTime());
        User user = users.saveAndFlush(User.create("cw-" + suffix, "synthetic-hash",
                "cw-" + suffix, UserRole.USER));
        Board board = boards.saveAndFlush(Board.create("board", "test", "cw-" + suffix, user));
        Post post = postRepository.saveAndFlush(Post.createMemberPost("test", "test", user, board));
        return new Fixture(user.getId(), post.getId());
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getUserId()).thenReturn(userId);
        return principal;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException("Concurrent test did not reach the barrier", error);
        }
    }

    private List<Throwable> concurrently(List<Callable<Void>> jobs) throws Exception {
        var executor = Executors.newFixedThreadPool(jobs.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Throwable>>();
            for (var job : jobs) {
                futures.add(executor.submit(() -> {
                    try {
                        job.call();
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                }));
            }
            List<Throwable> errors = new ArrayList<>();
            for (var future : futures) {
                Throwable error = future.get(45, TimeUnit.SECONDS);
                if (error != null) errors.add(error);
            }
            return errors;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private void assertCounts(Fixture fixture, int expected) {
        assertEquals(expected, postRepository.findById(fixture.postId()).orElseThrow().getCommentCount());
        assertEquals((long) expected, jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, fixture.postId()));
    }

    @Test
    void previousInsertThenDirtyUpdate_reproducesMysqlDeadlock() throws Exception {
        Fixture fixture = fixture();
        CyclicBarrier inserted = new CyclicBarrier(2);
        Callable<Void> previousOrder = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Post post = postRepository.findById(fixture.postId()).orElseThrow();
                User user = users.findById(fixture.userId()).orElseThrow();
                commentRepository.saveAndFlush(Comment.createMemberComment("baseline", user, post, null));
                // Both transactions now hold the FK shared lock on the same post.
                await(inserted);
                post.increaseCommentCount();
            });
            return null;
        };
        List<Throwable> errors = concurrently(List.of(previousOrder, previousOrder));
        assertEquals(1, errors.size(), errors.toString());
        boolean deadlock = false;
        for (Throwable cause = errors.getFirst(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213
                    && "40001".equals(sql.getSQLState())) deadlock = true;
        }
        assertTrue(deadlock, "The historical-order control must fail specifically with MySQL 1213/40001");
        assertCounts(fixture, 1);
    }

    @Test
    void parentLockFirst_allConcurrentMemberAndGuestRepliesCommitWithoutLostCount() throws Exception {
        Fixture fixture = fixture();
        Long parentId = comments.createMemberComment(fixture.postId(),
                new MemberCommentCreateRequest("parent"), principal(fixture.userId()), null);
        int writers = 20;
        CyclicBarrier start = new CyclicBarrier(writers);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
            boolean member = index % 2 == 0;
            jobs.add(() -> {
                await(start);
                if (member) {
                    comments.createMemberComment(fixture.postId(), new MemberCommentCreateRequest("member"),
                            principal(fixture.userId()), parentId);
                } else {
                    comments.createGuestComment(fixture.postId(),
                            new GuestCommentCreateRequest("guest", "guest", "test-password"), parentId);
                }
                return null;
            });
        }
        List<Throwable> errors = concurrently(jobs);
        assertTrue(errors.isEmpty(), errors.toString());
        assertCounts(fixture, writers + 1);
        assertEquals((long) writers, jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE post_id = ? AND parent_id = ?", Long.class,
                fixture.postId(), parentId));
    }

    @Test
    void rollback_revertsBothInsertAndCountAndReleasesParentLock() {
        Fixture fixture = fixture();
        assertThrows(IllegalStateException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    comments.createMemberComment(fixture.postId(), new MemberCommentCreateRequest("rolled back"),
                            principal(fixture.userId()), null);
                    throw new IllegalStateException("Synthetic rollback");
                }));
        assertCounts(fixture, 0);
        comments.createMemberComment(fixture.postId(), new MemberCommentCreateRequest("committed"),
                principal(fixture.userId()), null);
        assertCounts(fixture, 1);
    }

    @Test
    void lockingRead_requiresTheCallersWriteTransaction() {
        Fixture fixture = fixture();
        assertThrows(IllegalTransactionStateException.class, () -> posts.getPostForUpdate(fixture.postId()));
    }
}
