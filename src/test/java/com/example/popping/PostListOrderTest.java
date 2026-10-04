package com.example.popping;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.dto.PostListItemResponse;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real MySQL, because the order only exists in the query: without an ORDER BY,
 * InnoDB returned the board's oldest posts on the first page.
 */
@SpringBootTest
@Transactional
class PostListOrderTest {

    @Autowired PostRepository postRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("게시판 목록: 최신 글(id 큰 순)부터 보이고, 다음 페이지가 이어진다")
    void boardList_newestFirst_acrossPages() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Long oldest = save(user, board, "first");
        Long middle = save(user, board, "second");
        Long newest = save(user, board, "third");

        Slice<PostListItemResponse> firstPage = postRepository.findPostListByBoard(board, PageRequest.of(0, 2));
        Slice<PostListItemResponse> secondPage = postRepository.findPostListByBoard(board, PageRequest.of(1, 2));

        assertThat(ids(firstPage)).containsExactly(newest, middle);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(ids(secondPage)).containsExactly(oldest);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("게시판 목록: 마지막 페이지가 정확히 찰 때 다음 페이지가 없다")
    void boardList_exactlyFullLastPage_hasNoNext() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Long older = save(user, board, "first");
        Long newer = save(user, board, "second");

        Slice<PostListItemResponse> page = postRepository.findPostListByBoard(board, PageRequest.of(0, 2));

        assertThat(ids(page)).containsExactly(newer, older);
        assertThat(page.hasNext()).isFalse();
    }

    private Long save(User user, Board board, String title) {
        return postRepository.saveAndFlush(Post.createMemberPost(title, "content", user, board)).getId();
    }

    private static List<Long> ids(Slice<PostListItemResponse> slice) {
        return slice.getContent().stream().map(PostListItemResponse::id).toList();
    }
}
