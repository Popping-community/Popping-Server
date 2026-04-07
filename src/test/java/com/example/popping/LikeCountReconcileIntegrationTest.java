package com.example.popping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Like;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.domain.UserRole;
import com.example.popping.domain.Board;
import com.example.popping.dto.LikeRequest;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;
import com.example.popping.scheduler.LikeCountReconcileScheduler;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class LikeCountReconcileIntegrationTest {

    @Autowired LikeCountReconcileScheduler scheduler;
    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired UserRepository userRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired LikeService likeService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("post.likeCount가 likes 테이블과 불일치할 때 배치가 보정한다")
    void post_likeCount_불일치_시_보정된다() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Post post = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board));

        likeService.addLike(
                new LikeRequest(post.getId(), Like.TargetType.POST, Like.Type.LIKE, null),
                principal(user.getId()));

        // 강제 불일치: likeCount를 0으로 덮어써 likes 테이블과 어긋나게 만든다
        jdbcTemplate.update("UPDATE post SET like_count = 0 WHERE id = ?", post.getId());
        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isEqualTo(0);

        // when
        scheduler.reconcileLikeCounts();

        // then
        assertThat(postRepository.findById(post.getId()).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("comment.likeCount가 likes 테이블과 불일치할 때 배치가 보정한다")
    void comment_likeCount_불일치_시_보정된다() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Post post = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board));
        Comment comment = commentRepository.saveAndFlush(
                Comment.createMemberComment("comment", user, post, null));

        likeService.addLike(
                new LikeRequest(comment.getId(), Like.TargetType.COMMENT, Like.Type.LIKE, null),
                principal(user.getId()));

        // 강제 불일치
        jdbcTemplate.update("UPDATE comment SET like_count = 0 WHERE id = ?", comment.getId());
        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getLikeCount()).isEqualTo(0);

        // when
        scheduler.reconcileLikeCounts();

        // then
        assertThat(commentRepository.findById(comment.getId()).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("불일치가 없으면 배치 실행 후에도 likeCount가 변경되지 않는다")
    void 불일치_없으면_likeCount_변경_없다() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Post post = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board));

        likeService.addLike(
                new LikeRequest(post.getId(), Like.TargetType.POST, Like.Type.LIKE, null),
                principal(user.getId()));

        int likeCountBefore = postRepository.findById(post.getId()).orElseThrow().getLikeCount();

        // when
        scheduler.reconcileLikeCounts();

        // then
        int likeCountAfter = postRepository.findById(post.getId()).orElseThrow().getLikeCount();
        assertThat(likeCountAfter).isEqualTo(likeCountBefore);
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }
}
