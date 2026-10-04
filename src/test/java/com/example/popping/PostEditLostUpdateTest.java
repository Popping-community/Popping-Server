package com.example.popping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserRole;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A post edit loads the entity, changes title and content, and lets dirty checking flush it.
 * A like committed between that load and the flush must survive: the edit's UPDATE may only
 * write the columns the edit changed. Commits for real, so it runs against MySQL without
 * a rolled-back test transaction.
 */
@SpringBootTest
class PostEditLostUpdateTest {

    @Autowired PostRepository postRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("글 수정 중에 커밋된 좋아요는 수정 저장 뒤에도 남는다 (좋아요 수·반응 버전)")
    void likeCommittedDuringEdit_survivesTheEditFlush() {
        String unique = String.valueOf(System.nanoTime());
        User user = userRepository.saveAndFlush(
                User.create("login-" + unique, "nick-" + unique, "pw-" + unique, UserRole.USER));
        Board board = boardRepository.saveAndFlush(
                Board.create("board-" + unique, "desc", "slug-" + unique, user));
        Long postId = postRepository.saveAndFlush(
                Post.createMemberPost("title", "content", user, board)).getId();

        TransactionTemplate editTx = new TransactionTemplate(transactionManager);
        TransactionTemplate likeTx = new TransactionTemplate(transactionManager);
        likeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        editTx.executeWithoutResult(status -> {
            Post post = postRepository.findById(postId).orElseThrow();
            // Another request likes the post and commits while this edit is in progress.
            likeTx.executeWithoutResult(inner -> postRepository.updateLikeCount(postId, 1));
            post.updateAsMember("edited title", "edited content");
        });

        LikeCountView counts = postRepository.findLikeCountsById(postId);
        assertThat(postRepository.findById(postId).orElseThrow().getTitle()).isEqualTo("edited title");
        assertThat(counts.getLikeCount()).isEqualTo(1);
        assertThat(counts.getReactionVersion()).isEqualTo(1);
    }
}
