package com.example.popping;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.Board;
import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The refetch endpoint against MySQL with committed data, so commit callbacks really run, and
 * with the Primary and Replica DataSources counted, so the routing is observed, not assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReactionApiTest {

    private static final AtomicInteger PRIMARY_CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger REPLICA_CONNECTIONS = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("txTemplate") TransactionTemplate txTemplate;
    @Autowired @Qualifier("readOnlyTx") TransactionTemplate readOnlyTx;

    private User user;
    private Board board;
    private Post post;
    private Post otherPost;
    private Comment comment;
    private Comment foreignComment;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        board = boardRepository.saveAndFlush(Board.create("board-" + unique, "desc", "slug-" + unique, user));
        post = postRepository.saveAndFlush(Post.createMemberPost("title", "content", user, board));
        otherPost = postRepository.saveAndFlush(Post.createMemberPost("other", "content", user, board));
        comment = commentRepository.saveAndFlush(Comment.createMemberComment("c", user, post, null));
        foreignComment = commentRepository.saveAndFlush(Comment.createMemberComment("x", user, otherPost, null));
        txTemplate.executeWithoutResult(status -> {
            postRepository.updateLikeCount(post.getId(), 1);
            postRepository.updateDislikeCount(post.getId(), 1);
            commentRepository.updateLikeCount(comment.getId(), 1);
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM comment WHERE id IN (?, ?)", comment.getId(), foreignComment.getId());
        jdbcTemplate.update("DELETE FROM post WHERE id IN (?, ?)", post.getId(), otherPost.getId());
        jdbcTemplate.update("DELETE FROM board WHERE id = ?", board.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    @DisplayName("비로그인으로 글·댓글의 현재 숫자와 버전을 받고, 다른 글의 댓글 id는 빠지며, sticky 쿠키는 붙지 않는다")
    void returnsCountsAndDropsForeignComments() throws Exception {
        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), post.getId())
                        .param("commentIds", comment.getId() + "," + foreignComment.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                // The first-visit guestIdentifier cookie is expected. A sticky-primary cookie would
                // be set after commit and push a polling viewer off the comment cache.
                .andExpect(header().stringValues("Set-Cookie", not(hasItem(containsString("STICKY_PRIMARY")))))
                .andExpect(jsonPath("$.post.likeCount").value(1))
                .andExpect(jsonPath("$.post.dislikeCount").value(1))
                .andExpect(jsonPath("$.post.reactionVersion").value(2))
                .andExpect(jsonPath("$.comments", hasSize(1)))
                .andExpect(jsonPath("$.comments[0].commentId").value(comment.getId()))
                .andExpect(jsonPath("$.comments[0].reactionVersion").value(1));
    }

    @Test
    @DisplayName("Primary에서 읽는다 (대조: 읽기 전용 트랜잭션은 Replica로 간다)")
    void readsFromPrimary() throws Exception {
        resetConnectionCounts();
        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), post.getId()))
                .andExpect(status().isOk());
        int primaryForRefetch = PRIMARY_CONNECTIONS.get();
        int replicaForRefetch = REPLICA_CONNECTIONS.get();

        resetConnectionCounts();
        readOnlyTx.executeWithoutResult(status -> postRepository.findLikeCountsById(post.getId()));

        assertThat(primaryForRefetch).isPositive();
        assertThat(replicaForRefetch).isZero();
        assertThat(REPLICA_CONNECTIONS.get()).isPositive();
        assertThat(PRIMARY_CONNECTIONS.get()).isZero();
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션 안에서 불려도 새 트랜잭션을 열어 Primary에서 읽는다")
    void insideReadOnlyTransaction_stillReadsPrimary(@Autowired com.example.popping.service.ReactionReadService service) {
        resetConnectionCounts();

        readOnlyTx.executeWithoutResult(status -> service.getReactionCounts(post.getId(), null));

        assertThat(PRIMARY_CONNECTIONS.get()).isPositive();
    }

    @Test
    @DisplayName("조회해도 조회수는 늘지 않는다")
    void doesNotCountAsView() throws Exception {
        long viewsBefore = postRepository.findById(post.getId()).orElseThrow().getViewCount();

        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), post.getId()))
                .andExpect(status().isOk());

        assertThat(postRepository.findById(post.getId()).orElseThrow().getViewCount()).isEqualTo(viewsBefore);
    }

    @Test
    @DisplayName("없는 글은 404, 댓글 id 101개는 400, 숫자가 아닌 id는 400")
    void errors() throws Exception {
        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), Long.MAX_VALUE))
                .andExpect(status().isNotFound());

        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 101; i++) {
            ids.append(i == 1 ? "" : ",").append(i);
        }
        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), post.getId())
                        .param("commentIds", ids.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/boards/{slug}/{postId}/reactions", board.getSlug(), post.getId())
                        .param("commentIds", "abc"))
                .andExpect(status().isBadRequest());
    }

    private static void resetConnectionCounts() {
        PRIMARY_CONNECTIONS.set(0);
        REPLICA_CONNECTIONS.set(0);
    }

    /** Counts connections handed out by the Primary and Replica pools behind the routing proxy. */
    @TestConfiguration
    static class CountingDataSources {

        @Bean
        static BeanPostProcessor countConnections() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("writeDataSource".equals(beanName)) {
                        return counting((DataSource) bean, PRIMARY_CONNECTIONS);
                    }
                    if ("readDataSource".equals(beanName)) {
                        return counting((DataSource) bean, REPLICA_CONNECTIONS);
                    }
                    return bean;
                }
            };
        }

        private static DataSource counting(DataSource target, AtomicInteger counter) {
            return new DelegatingDataSource(target) {
                @Override
                public Connection getConnection() throws SQLException {
                    counter.incrementAndGet();
                    return super.getConnection();
                }
            };
        }
    }
}
