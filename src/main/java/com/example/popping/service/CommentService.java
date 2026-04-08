package com.example.popping.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;

import com.example.popping.event.CommentCacheEvictEvent;

import com.example.popping.domain.*;
import com.example.popping.dto.CommentPageResponse;
import com.example.popping.dto.CommentResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.MyReactionView;
import com.example.popping.repository.CommentTreeRowView;
import com.example.popping.repository.LikeRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

    public static final int COMMENTS_SIZE = 100;
    private static final String COMMENT_FIRST_PAGE_CACHE = "commentFirstPage";

    private final PostService postService;
    private final UserService userService;
    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final PasswordEncoder guestPasswordEncoder;
    private final CacheManager cacheManager;
    private final TransactionTemplate readOnlyTx;
    private final ApplicationEventPublisher eventPublisher;
    private final GuestIdentifierService guestIdentifierService;

    public Long createMemberComment(Long postId,
                                    MemberCommentCreateRequest dto,
                                    UserPrincipal principal,
                                    Long parentId) {

        Post post = postService.getPost(postId);
        Comment parent = getParentComment(parentId);

        User user = userService.getLoginUserById(principal.getUserId());

        Comment comment = Comment.createMemberComment(dto.content(), user, post, parent);
        comment = commentRepository.save(comment);

        post.increaseCommentCount();
        evictFirstPageCacheByPostId(postId);
        return comment.getId();
    }

    public Long createGuestComment(Long postId,
                                   GuestCommentCreateRequest dto,
                                   Long parentId) {

        Post post = postService.getPost(postId);
        Comment parent = getParentComment(parentId);

        String hashedPassword = guestPasswordEncoder.encode(dto.guestPassword());

        Comment comment = Comment.createGuestComment(
                dto.content(),
                dto.guestNickname(),
                hashedPassword,
                post,
                parent
        );
        comment = commentRepository.save(comment);

        post.increaseCommentCount();
        evictFirstPageCacheByPostId(postId);
        return comment.getId();
    }

    public void deleteComment(Long commentId, UserPrincipal principal) {
        Comment comment = getComment(commentId);

        User user = userService.getLoginUserById(principal.getUserId());
        validateMemberAuthor(comment, user);

        Long postId = comment.getPost().getId();
        comment.getPost().decreaseCommentCount();
        commentRepository.delete(comment);
        evictFirstPageCacheByPostId(postId);
    }

    public void deleteCommentAsGuest(Long commentId, String password) {
        Comment comment = getComment(commentId);

        validateGuestComment(comment);
        validateGuestPassword(comment, password);

        Long postId = comment.getPost().getId();
        comment.getPost().decreaseCommentCount();
        commentRepository.delete(comment);
        evictFirstPageCacheByPostId(postId);
    }

    public void updateLikeCount(Long targetId, int delta) {
        commentRepository.updateLikeCount(targetId, delta);
    }

    public void updateDislikeCount(Long targetId, int delta) {
        commentRepository.updateDislikeCount(targetId, delta);
    }

    @Transactional(readOnly = true)
    public CommentPageResponse getCommentPage(Long postId, int page, UserPrincipal principal, String guestIdentifier) {
        CommentPageResponse base = (page == 0)
                ? getFirstPageCommon(postId)
                : buildCommentPage(postId, page);

        if (page == 0) {
            base = mergeLikeCounts(base);
        }

        return mergePersonalReaction(base, principal, resolveGuestIdentifier(guestIdentifier));
    }

    private String resolveGuestIdentifier(String raw) {
        if (raw == null) return null;
        return guestIdentifierService.extractUuid(raw).orElse(raw);
    }

    @Transactional(readOnly = true)
    public Comment getComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new CustomAppException(
                        ErrorType.COMMENT_NOT_FOUND,
                        "해당 댓글이 존재하지 않습니다: " + commentId
                ));
    }

    private Comment getParentComment(Long parentId) {
        if (parentId == null) return null;
        return getComment(parentId);
    }

    private CommentPageResponse getFirstPageCommon(Long postId) {
        Cache cache = cacheManager.getCache(COMMENT_FIRST_PAGE_CACHE);
        if (cache == null) {
            return buildCommentPage(postId, 0);
        }

        return cache.get(postId, () -> readOnlyTx.execute(status -> buildCommentPage(postId, 0)));
    }

    private CommentPageResponse buildCommentPage(Long postId, int page) {
        Post post = postService.getPost(postId);

        int totalComments = post.getCommentCount();
        int totalPages = totalComments == 0 ? 0 : (int) Math.ceil((double) totalComments / COMMENTS_SIZE);

        boolean hasPrevious = page > 0;
        boolean hasNext = totalPages != 0 && page < totalPages - 1;

        int offset = page * COMMENTS_SIZE;

        List<CommentTreeRowView> rows = commentRepository.findPagedCommentTree(postId, COMMENTS_SIZE, offset);
        List<CommentResponse> comments = buildCommentTree(rows);

        return new CommentPageResponse(
                comments,
                totalComments,
                page,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> buildCommentTree(List<CommentTreeRowView> rows) {
        if (rows == null || rows.isEmpty()) return List.of();

        Set<Long> userIds = rows.stream()
                .map(CommentTreeRowView::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> userIdToNickname = userService.getUserIdToNicknameMap(userIds);

        Map<Long, CommentNode> nodeMap = new LinkedHashMap<>();
        List<CommentNode> roots = new ArrayList<>();

        for (CommentTreeRowView row : rows) {
            CommentResponse base = CommentResponse.mapToResponse(row, userIdToNickname, false, false);
            CommentNode node = new CommentNode(base);
            nodeMap.put(base.id(), node);

            if (base.parentId() == null) {
                roots.add(node);
            } else {
                CommentNode parent = nodeMap.get(base.parentId());
                if (parent != null) {
                    parent.children.add(node);
                } else {
                    // 페이징으로 부모가 누락된 경우 방어적으로 루트에 둠
                    roots.add(node);
                }
            }
        }

        return roots.stream()
                .map(CommentNode::toResponse)
                .toList();
    }

    private CommentPageResponse mergeLikeCounts(CommentPageResponse page) {
        if (page == null || page.comments().isEmpty()) return page;

        Set<Long> commentIds = new LinkedHashSet<>();
        collectCommentIds(page.comments(), commentIds);

        Map<Long, CommentRepository.LikeCount> countMap = commentRepository.findLikeCountsByIds(commentIds)
                .stream()
                .collect(Collectors.toMap(CommentRepository.LikeCount::getId, c -> c));

        List<CommentResponse> updated = page.comments().stream()
                .map(comment -> applyLikeCount(comment, countMap))
                .toList();

        return new CommentPageResponse(
                updated,
                page.totalComments(),
                page.currentPage(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }

    private CommentResponse applyLikeCount(CommentResponse comment, Map<Long, CommentRepository.LikeCount> countMap) {
        CommentRepository.LikeCount counts = countMap.get(comment.id());
        int likeCount    = counts != null ? counts.getLikeCount()    : comment.likeCount();
        int dislikeCount = counts != null ? counts.getDislikeCount() : comment.dislikeCount();

        List<CommentResponse> updatedChildren = comment.children().stream()
                .map(child -> applyLikeCount(child, countMap))
                .toList();

        return new CommentResponse(
                comment.id(),
                comment.content(),
                comment.authorName(),
                comment.authorId(),
                comment.guestNickname(),
                likeCount,
                dislikeCount,
                comment.likedByMe(),
                comment.dislikedByMe(),
                comment.parentId(),
                comment.depth(),
                updatedChildren
        );
    }

    private CommentPageResponse mergePersonalReaction(CommentPageResponse page,
                                                     UserPrincipal principal,
                                                     String guestIdentifier) {
        if (page == null || page.comments().isEmpty()) return page;
        if (principal == null && (guestIdentifier == null || guestIdentifier.isBlank())) return page;

        Set<Long> commentIds = new LinkedHashSet<>();
        collectCommentIds(page.comments(), commentIds);

        String targetType = Like.TargetType.COMMENT.name();
        List<MyReactionView> myReactionViews = (principal != null)
                ? likeRepository.findReactionForMember(commentIds, targetType, principal.getUserId())
                : likeRepository.findReactionForGuest(commentIds, targetType, guestIdentifier);

        Map<Long, MyReactionView> myReactionViewMap = myReactionViews.stream()
                .collect(Collectors.toMap(MyReactionView::getTargetId, s -> s));

        List<CommentResponse> merged = page.comments().stream()
                .map(comment -> applyPersonalReaction(comment, myReactionViewMap))
                .toList();

        return new CommentPageResponse(
                merged,
                page.totalComments(),
                page.currentPage(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }

    private CommentResponse applyPersonalReaction(CommentResponse comment,
                                                  Map<Long, MyReactionView> myReactionViewMap) {
        MyReactionView s = myReactionViewMap.get(comment.id());

        boolean likedByMe    = s != null && s.getLikedByMe()    == 1;
        boolean dislikedByMe = s != null && s.getDislikedByMe() == 1;

        List<CommentResponse> mergedChildren = comment.children().stream()
                .map(child -> applyPersonalReaction(child, myReactionViewMap))
                .toList();

        return new CommentResponse(
                comment.id(),
                comment.content(),
                comment.authorName(),
                comment.authorId(),
                comment.guestNickname(),
                comment.likeCount(),
                comment.dislikeCount(),
                likedByMe,
                dislikedByMe,
                comment.parentId(),
                comment.depth(),
                mergedChildren
        );
    }

    private void collectCommentIds(List<CommentResponse> comments, Set<Long> collector) {
        for (CommentResponse comment : comments) {
            collector.add(comment.id());
            if (!comment.children().isEmpty()) {
                collectCommentIds(comment.children(), collector);
            }
        }
    }

    private void validateMemberAuthor(Comment comment, User user) {
        if (!comment.isAuthor(user)) {
            throw new CustomAppException(
                    ErrorType.ACCESS_DENIED,
                    "댓글 작성자가 아닙니다." + user.getLoginId()
            );
        }
    }

    private void validateGuestComment(Comment comment) {
        if (!comment.isGuest()) {
            throw new CustomAppException(
                    ErrorType.ACCESS_DENIED,
                    "회원이 작성한 댓글은 비밀번호로 삭제할 수 없습니다."
            );
        }
    }

    private void validateGuestPassword(Comment comment, String rawPassword) {
        if (!guestPasswordEncoder.matches(rawPassword, comment.getGuestPasswordHash())) {
            throw new CustomAppException(
                    ErrorType.ACCESS_DENIED,
                    "비밀번호가 일치하지 않습니다."
            );
        }
    }

    private void evictFirstPageCacheByPostId(Long postId) {
        if (postId == null) return;
        eventPublisher.publishEvent(new CommentCacheEvictEvent(postId));
    }

    private static final class CommentNode {
        private final CommentResponse base;
        private final List<CommentNode> children = new ArrayList<>();

        private CommentNode(CommentResponse base) {
            this.base = base;
        }

        private CommentResponse toResponse() {
            return new CommentResponse(
                    base.id(),
                    base.content(),
                    base.authorName(),
                    base.authorId(),
                    base.guestNickname(),
                    base.likeCount(),
                    base.dislikeCount(),
                    base.likedByMe(),
                    base.dislikedByMe(),
                    base.parentId(),
                    base.depth(),
                    children.stream().map(CommentNode::toResponse).toList()
            );
        }
    }
}