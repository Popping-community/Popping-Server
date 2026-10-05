package com.example.popping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.popping.domain.Board;
import com.example.popping.domain.Comment;
import com.example.popping.domain.Like;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.dto.LikeRequest;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reaction version lets a client drop a like broadcast older than the counts it already
 * shows. It must move in the same UPDATE as the counts, so this runs against MySQL.
 */
@SpringBootTest
@Transactional
class ReactionVersionTest {

    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired UserRepository userRepository;
    @Autowired LikeService likeService;

    private Post post;
    private Comment comment;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        post = postRepository.saveAndFlush(Post.createMemberPost("title", "content", user, board));
        comment = commentRepository.saveAndFlush(Comment.createMemberComment("comment", user, post, null));
    }

    @Test
    @DisplayName("게시글: 새로 만든 글의 버전은 0이고, 좋아요·싫어요 변경마다 같은 버전이 1씩 오른다")
    void postVersion_movesWithEveryCountChange() {
        assertThat(postRepository.findLikeCountsById(post.getId()).getReactionVersion()).isZero();

        postRepository.updateLikeCount(post.getId(), 1);
        LikeCountView afterLike = postRepository.findLikeCountsById(post.getId());
        postRepository.updateDislikeCount(post.getId(), 1);
        LikeCountView afterDislike = postRepository.findLikeCountsById(post.getId());
        postRepository.updateLikeCount(post.getId(), -1);
        LikeCountView afterUnlike = postRepository.findLikeCountsById(post.getId());

        assertThat(afterLike.getLikeCount()).isEqualTo(1);
        assertThat(afterLike.getReactionVersion()).isEqualTo(1);
        assertThat(afterDislike.getDislikeCount()).isEqualTo(1);
        assertThat(afterDislike.getReactionVersion()).isEqualTo(2);
        assertThat(afterUnlike.getLikeCount()).isZero();
        assertThat(afterUnlike.getReactionVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("댓글: 좋아요·싫어요 변경마다 같은 버전이 1씩 오르고, 목록 조회에도 버전이 실린다")
    void commentVersion_movesWithEveryCountChange() {
        commentRepository.updateLikeCount(comment.getId(), 1);
        commentRepository.updateDislikeCount(comment.getId(), 1);

        LikeCountView single = commentRepository.findLikeCountsById(comment.getId());
        CommentRepository.LikeCount listed =
                commentRepository.findLikeCountsByIds(java.util.List.of(comment.getId())).get(0);

        assertThat(single.getReactionVersion()).isEqualTo(2);
        assertThat(listed.getReactionVersion()).isEqualTo(2);
        assertThat(listed.getLikeCount()).isEqualTo(single.getLikeCount()).isEqualTo(1);
        assertThat(listed.getDislikeCount()).isEqualTo(single.getDislikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사람이 좋아요를 다시 눌러 아무것도 바뀌지 않으면 버전도 그대로다")
    void repeatedLike_leavesVersionUnchanged() {
        LikeRequest like = new LikeRequest(post.getId(), Like.TargetType.POST, Like.Type.LIKE);
        String guest = "guest-" + post.getId();

        likeService.addLike(like, null, guest);
        likeService.addLike(like, null, guest);

        LikeCountView counts = postRepository.findLikeCountsById(post.getId());
        assertThat(counts.getLikeCount()).isEqualTo(1);
        assertThat(counts.getReactionVersion()).isEqualTo(1);
    }
}
