package com.example.popping;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;
import com.example.popping.service.GuestIdentifierService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guest behind a like is the one the server verified from the signed cookie, whatever the
 * request body says. Before the fix every distinct body value counted as a new guest, so one
 * client could add likes without limit and remove other guests' likes.
 * Reads go through JdbcTemplate outside a transaction, which the routing proxy sends to the Primary.
 */
@SpringBootTest(properties = "app.test-api.likes.enabled=true")
@AutoConfigureMockMvc
class LikeActorIdentityTest {

    private static final String COOKIE = "guestIdentifier";

    @Autowired MockMvc mvc;
    @Autowired GuestIdentifierService guestIdentifierService;
    @Autowired UserRepository userRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired PostRepository postRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private User user;
    private Board board;
    private Post target;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        board = boardRepository.saveAndFlush(Board.create("board-" + unique, "desc", "slug-" + unique, user));
        target = postRepository.saveAndFlush(Post.createMemberPost("title", "content", user, board));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM likes WHERE target_type = 'POST' AND target_id = ?", target.getId());
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", target.getId());
        jdbcTemplate.update("DELETE FROM board WHERE id = ?", board.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    @DisplayName("같은 쿠키로 본문의 게스트 값을 바꿔 여러 번 눌러도 좋아요는 1개다")
    void rotatingBodyIdentifier_countsOnce() throws Exception {
        String cookie = guestIdentifierService.generate();

        for (int i = 0; i < 5; i++) {
            like("add", cookie, "forged-" + i);
        }

        assertThat(likeCount()).isEqualTo(1);
        assertThat(guestRows()).containsExactly(uuidOf(cookie));
    }

    @Test
    @DisplayName("다른 게스트의 UUID를 본문에 넣어 취소해도 그 사람의 좋아요는 지워지지 않는다")
    void cannotRemoveAnotherGuestsLike() throws Exception {
        String victim = guestIdentifierService.generate();
        String attacker = guestIdentifierService.generate();
        like("add", victim, null);

        like("remove", attacker, uuidOf(victim));
        like("remove", attacker, victim);

        assertThat(likeCount()).isEqualTo(1);
        assertThat(guestRows()).containsExactly(uuidOf(victim));
    }

    @Test
    @DisplayName("서명이 맞지 않는 쿠키 값은 쓰이지 않고 서버가 새로 발급한 신원으로 기록된다")
    void unsignedCookie_isNotUsedAsIdentity() throws Exception {
        like("add", "forged-cookie", "forged-body");

        assertThat(likeCount()).isEqualTo(1);
        assertThat(guestRows()).singleElement()
                .isNotIn("forged-cookie", "forged-body")
                .matches(id -> id.matches("[0-9a-f-]{36}"));
    }

    private void like(String action, String cookie, String bodyIdentifier) throws Exception {
        String extra = bodyIdentifier == null ? "" : ", \"guestIdentifier\": \"" + bodyIdentifier + "\"";
        mvc.perform(post("/api/test/likes/" + action)
                        .cookie(new Cookie(COOKIE, cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\": " + target.getId()
                                + ", \"targetType\": \"POST\", \"type\": \"LIKE\"" + extra + "}"))
                .andExpect(status().isOk());
    }

    private String uuidOf(String cookie) {
        return guestIdentifierService.extractUuid(cookie).orElseThrow();
    }

    private int likeCount() {
        return jdbcTemplate.queryForObject("SELECT like_count FROM post WHERE id = ?", Integer.class, target.getId());
    }

    private java.util.List<String> guestRows() {
        return jdbcTemplate.queryForList(
                "SELECT guest_identifier FROM likes WHERE target_type = 'POST' AND target_id = ? AND type = 'LIKE'",
                String.class, target.getId());
    }
}
