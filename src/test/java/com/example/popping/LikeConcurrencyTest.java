package com.example.popping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.example.popping.domain.*;
import com.example.popping.dto.LikeRequest;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class LikeConcurrencyTest {

    @Autowired LikeService likeService;
    @Autowired LikeRepository likeRepository;
    @Autowired PostRepository postRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    BoardRepository boardRepository;
    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("동일 사용자-동일 게시글에 대한 동시 addLike 요청은 멱등하게 처리되어야 한다")
    void sameUser_samePost_addLike_concurrently() throws Exception {
        String unique = String.valueOf(System.nanoTime());

        User user = userRepository.saveAndFlush(
                User.create(
                        "login-" + unique,
                        "nick-" + unique,
                        "pw-" + unique,
                        UserRole.USER
                )
        );

        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user)
        );

        Post savedPost = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board)
        );

        Long userId = user.getId();
        Long postId = savedPost.getId();

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        LikeRequest request = new LikeRequest(postId, Like.TargetType.POST, Like.Type.LIKE, null);
        UserPrincipal principal = principal(userId);

        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    likeService.addLike(request, principal);
                    success.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        Post post = postRepository.findById(postId).orElseThrow();

        long likeRowCount = likeRepository.countByTargetIdAndTargetTypeAndTypeAndUser_Id(
                postId, Like.TargetType.POST, Like.Type.LIKE, userId
        );

        System.out.println("errors = " + errors.size());
        System.out.println("success = " + success.get());
        System.out.println("post.likeCount = " + post.getLikeCount());
        System.out.println("likeRowCount = " + likeRowCount);

        assertThat(errors).isEmpty();
        assertThat(success.get()).isEqualTo(threadCount);
        assertThat(likeRowCount).isEqualTo(1);
        assertThat(post.getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 사용자-동일 게시글에 대한 동시 removeLike 요청은 멱등하게 처리되어야 한다")
    void sameUser_samePost_removeLike_concurrently() throws Exception {
        String unique = String.valueOf(System.nanoTime());

        User user = userRepository.saveAndFlush(
                User.create(
                        "login-" + unique,
                        "nick-" + unique,
                        "pw-" + unique,
                        UserRole.USER
                )
        );

        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user)
        );

        Post savedPost = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board)
        );

        Long userId = user.getId();
        Long postId = savedPost.getId();

        // given: 먼저 좋아요 1개를 만들어 둔다
        likeService.addLike(
                new LikeRequest(postId, Like.TargetType.POST, Like.Type.LIKE, null),
                principal(userId)
        );

        em.clear();

        Post beforePost = postRepository.findById(postId).orElseThrow();
        long beforeLikeRowCount = likeRepository.countByTargetIdAndTargetTypeAndTypeAndUser_Id(
                postId, Like.TargetType.POST, Like.Type.LIKE, userId
        );

        assertThat(beforeLikeRowCount).isEqualTo(1);
        assertThat(beforePost.getLikeCount()).isEqualTo(1);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        LikeRequest request = new LikeRequest(postId, Like.TargetType.POST, Like.Type.LIKE, null);
        UserPrincipal principal = principal(userId);

        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    likeService.removeLike(request, principal);
                    success.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        em.clear();

        Post post = postRepository.findById(postId).orElseThrow();
        long likeRowCount = likeRepository.countByTargetIdAndTargetTypeAndTypeAndUser_Id(
                postId, Like.TargetType.POST, Like.Type.LIKE, userId
        );

        System.out.println("errors = " + errors.size());
        System.out.println("success = " + success.get());
        System.out.println("post.likeCount = " + post.getLikeCount());
        System.out.println("likeRowCount = " + likeRowCount);

        assertThat(errors).isEmpty();
        assertThat(success.get()).isEqualTo(threadCount);
        assertThat(likeRowCount).isEqualTo(0);
        assertThat(post.getLikeCount()).isEqualTo(0);
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }
}