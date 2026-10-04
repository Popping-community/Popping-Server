package com.example.popping.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.dto.ReactionCountsResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.PostRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reads current reaction counts so a client can correct what it shows after a reconnect or a
 * missed relay message.
 *
 * <p>Reads the Primary on purpose: a lagging Replica would hand back counts older than the
 * relayed messages the client already applied. {@code primaryReadTx} is a new, non-read-only
 * transaction, which the routing proxy sends there. This class deliberately has no
 * {@code @Transactional}: the sticky-primary aspect turns every annotated non-read-only
 * transaction into a sticky cookie, and a periodic poll would then push every viewer off the
 * comment cache. The programmatic transaction avoids that.
 *
 * <p>Not rate-limited; like the rest of the application it relies on the per-request cap.
 */
@Service
@RequiredArgsConstructor
public class ReactionReadService {

    static final int MAX_COMMENT_IDS = CommentService.COMMENTS_SIZE;

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final TransactionTemplate primaryReadTx;

    public ReactionCountsResponse getReactionCounts(Long postId, List<Long> commentIds) {
        Set<Long> ids = commentIds == null ? Set.of() : new LinkedHashSet<>(commentIds);
        if (ids.size() > MAX_COMMENT_IDS) {
            throw new CustomAppException(ErrorType.VALIDATION_ERROR,
                    "댓글은 한 번에 " + MAX_COMMENT_IDS + "개까지 조회할 수 있습니다.");
        }
        return primaryReadTx.execute(status -> {
            LikeCountView post = postRepository.findLikeCountsById(postId);
            if (post == null) {
                throw new CustomAppException(ErrorType.POST_NOT_FOUND);
            }
            // Only comments of this post: an id from another post is dropped, not reported.
            List<ReactionCountsResponse.CommentCounts> comments = ids.isEmpty() ? List.of()
                    : commentRepository.findLikeCountsByPostIdAndIds(postId, ids).stream()
                    .map(c -> new ReactionCountsResponse.CommentCounts(
                            c.getId(), c.getLikeCount(), c.getDislikeCount(), c.getReactionVersion()))
                    .toList();
            return new ReactionCountsResponse(
                    new ReactionCountsResponse.Counts(
                            post.getLikeCount(), post.getDislikeCount(), post.getReactionVersion()),
                    comments);
        });
    }
}
