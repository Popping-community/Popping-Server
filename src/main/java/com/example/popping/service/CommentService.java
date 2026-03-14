package com.example.popping.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.*;
import com.example.popping.dto.CommentPageResponse;
import com.example.popping.dto.CommentResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.CommentTreeRowView;

@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

    public static final int COMMENTS_SIZE = 100;
    private static final String COMMENT_FIRST_PAGE_CACHE = "commentFirstPage";

    private final PostService postService;
    private final UserService userService;
    private final LikeQueryService likeQueryService;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;

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

        String hashedPassword = passwordEncoder.encode(dto.guestPassword());

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
        evictFirstPageCacheByCommentId(targetId);
    }

    public void updateDislikeCount(Long targetId, int delta) {
        commentRepository.updateDislikeCount(targetId, delta);
        evictFirstPageCacheByCommentId(targetId);
    }

    @Transactional(readOnly = true)
    public CommentPageResponse getCommentPage(Long postId, int page, UserPrincipal principal, String guestIdentifier) {
        CommentPageResponse commonPage = (page == 0)
                ? getFirstPageCommon(postId)
                : buildCommentPage(postId, page);

        return mergeReactionState(commonPage, principal, guestIdentifier);
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

        CommentPageResponse cached = cache.get(postId, CommentPageResponse.class);
        if (cached != null) {
            return cached;
        }

        CommentPageResponse fresh = buildCommentPage(postId, 0);
        cache.put(postId, fresh);
        return fresh;
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

    private CommentPageResponse mergeReactionState(CommentPageResponse commonPage,
                                                   UserPrincipal principal,
                                                   String guestIdentifier) {
        if (commonPage == null || commonPage.comments().isEmpty()) {
            return commonPage;
        }

        if (principal == null && (guestIdentifier == null || guestIdentifier.isBlank())) {
            return commonPage;
        }

        Set<Long> commentIds = new LinkedHashSet<>();
        collectCommentIds(commonPage.comments(), commentIds);

        Map<Long, Set<Like.Type>> reactionMap =
                likeQueryService.getReactionMap(Like.TargetType.COMMENT, commentIds, principal, guestIdentifier);

        List<CommentResponse> mergedComments = commonPage.comments().stream()
                .map(comment -> applyReaction(comment, reactionMap))
                .toList();

        return new CommentPageResponse(
                mergedComments,
                commonPage.totalComments(),
                commonPage.currentPage(),
                commonPage.totalPages(),
                commonPage.hasNext(),
                commonPage.hasPrevious()
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

    private CommentResponse applyReaction(CommentResponse comment,
                                          Map<Long, Set<Like.Type>> reactionMap) {
        Set<Like.Type> reactions = reactionMap.get(comment.id());
        boolean likedByMe = reactions != null && reactions.contains(Like.Type.LIKE);
        boolean dislikedByMe = reactions != null && reactions.contains(Like.Type.DISLIKE);

        List<CommentResponse> mergedChildren = comment.children().stream()
                .map(child -> applyReaction(child, reactionMap))
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
        if (!passwordEncoder.matches(rawPassword, comment.getGuestPasswordHash())) {
            throw new CustomAppException(
                    ErrorType.ACCESS_DENIED,
                    "비밀번호가 일치하지 않습니다."
            );
        }
    }

    private void evictFirstPageCacheByPostId(Long postId) {
        Cache cache = cacheManager.getCache(COMMENT_FIRST_PAGE_CACHE);
        if (cache == null || postId == null) return;
        cache.evict(postId);
    }

    private void evictFirstPageCacheByCommentId(Long commentId) {
        Cache cache = cacheManager.getCache(COMMENT_FIRST_PAGE_CACHE);
        if (cache == null || commentId == null) return;

        Long postId = commentRepository.findPostIdByCommentId(commentId);
        if (postId != null) {
            cache.evict(postId);
        }
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