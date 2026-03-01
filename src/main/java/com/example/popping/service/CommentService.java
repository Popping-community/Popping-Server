package com.example.popping.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
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

    private final PostService postService;
    private final UserService userService;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;

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
        return comment.getId();
    }

    public void deleteComment(Long commentId, UserPrincipal principal) {
        Comment comment = getComment(commentId);

        User user = userService.getLoginUserById(principal.getUserId());
        validateMemberAuthor(comment, user);

        comment.getPost().decreaseCommentCount();
        commentRepository.delete(comment);
    }

    public void deleteCommentAsGuest(Long commentId, String password) {
        Comment comment = getComment(commentId);

        validateGuestComment(comment);
        validateGuestPassword(comment, password);

        comment.getPost().decreaseCommentCount();
        commentRepository.delete(comment);
    }

    public void updateLikeCount(Long targetId, int delta) {
        commentRepository.updateLikeCount(targetId, delta);
    }

    public void updateDislikeCount(Long targetId, int delta) {
        commentRepository.updateDislikeCount(targetId, delta);
    }

    @Transactional(readOnly = true)
    public CommentPageResponse getCommentPage(Long postId, int page) {
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
            CommentResponse base = CommentResponse.mapToResponse(row, userIdToNickname);
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
                    base.parentId(),
                    base.depth(),
                    children.stream().map(CommentNode::toResponse).toList()
            );
        }
    }
}