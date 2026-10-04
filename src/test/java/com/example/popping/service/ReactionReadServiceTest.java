package com.example.popping.service;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.dto.ReactionCountsResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.PostRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactionReadServiceTest {

    @Mock PostRepository postRepository;
    @Mock CommentRepository commentRepository;
    @Mock TransactionTemplate primaryReadTx;

    @InjectMocks ReactionReadService service;

    @BeforeEach
    void setUp() {
        when(primaryReadTx.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        LikeCountView postCounts = counts(5, 1, 9);
        when(postRepository.findLikeCountsById(1L)).thenReturn(postCounts);
    }

    @Test
    @DisplayName("글과 요청한 댓글의 현재 숫자·버전을 돌려준다")
    void returnsPostAndCommentCounts() {
        CommentRepository.LikeCount commentCounts = commentCounts(11L, 2, 0, 4);
        when(commentRepository.findLikeCountsByPostIdAndIds(eq(1L), anyCollection()))
                .thenReturn(List.of(commentCounts));

        ReactionCountsResponse response = service.getReactionCounts(1L, List.of(11L));

        assertThat(response.post()).isEqualTo(new ReactionCountsResponse.Counts(5, 1, 9));
        assertThat(response.comments()).containsExactly(new ReactionCountsResponse.CommentCounts(11L, 2, 0, 4));
    }

    @Test
    @DisplayName("댓글 id가 없으면 댓글 조회를 하지 않는다")
    void noCommentIds_skipsCommentQuery() {
        ReactionCountsResponse response = service.getReactionCounts(1L, null);

        assertThat(response.comments()).isEmpty();
        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("중복 id는 한 번만 조회한다")
    @SuppressWarnings("unchecked")
    void duplicateIds_queriedOnce() {
        service.getReactionCounts(1L, List.of(3L, 3L, 4L));

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(commentRepository).findLikeCountsByPostIdAndIds(eq(1L), ids.capture());
        assertThat(ids.getValue()).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("서로 다른 댓글 id가 100개를 넘으면 400으로 거절한다")
    void tooManyIds_rejected() {
        List<Long> ids = LongStream.rangeClosed(1, ReactionReadService.MAX_COMMENT_IDS + 1).boxed().toList();

        assertThatThrownBy(() -> service.getReactionCounts(1L, ids))
                .isInstanceOf(CustomAppException.class)
                .extracting(e -> ((CustomAppException) e).getErrorType())
                .isEqualTo(ErrorType.VALIDATION_ERROR);
        verifyNoInteractions(postRepository, commentRepository);
    }

    @Test
    @DisplayName("100개는 허용한다")
    void exactlyMaxIds_allowed() {
        List<Long> ids = IntStream.rangeClosed(1, ReactionReadService.MAX_COMMENT_IDS)
                .mapToObj(Long::valueOf).toList();

        service.getReactionCounts(1L, ids);

        verify(commentRepository).findLikeCountsByPostIdAndIds(eq(1L), anyCollection());
    }

    @Test
    @DisplayName("없는 글이면 POST_NOT_FOUND")
    void missingPost_notFound() {
        when(postRepository.findLikeCountsById(2L)).thenReturn(null);

        assertThatThrownBy(() -> service.getReactionCounts(2L, List.of(1L)))
                .isInstanceOf(CustomAppException.class)
                .extracting(e -> ((CustomAppException) e).getErrorType())
                .isEqualTo(ErrorType.POST_NOT_FOUND);
    }

    private static LikeCountView counts(int like, int dislike, long version) {
        LikeCountView view = mock(LikeCountView.class);
        when(view.getLikeCount()).thenReturn(like);
        when(view.getDislikeCount()).thenReturn(dislike);
        when(view.getReactionVersion()).thenReturn(version);
        return view;
    }

    private static CommentRepository.LikeCount commentCounts(Long id, int like, int dislike, long version) {
        CommentRepository.LikeCount view = mock(CommentRepository.LikeCount.class);
        when(view.getId()).thenReturn(id);
        when(view.getLikeCount()).thenReturn(like);
        when(view.getDislikeCount()).thenReturn(dislike);
        when(view.getReactionVersion()).thenReturn(version);
        return view;
    }
}
