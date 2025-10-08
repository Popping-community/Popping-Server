package com.example.popping.service;

import java.math.BigInteger;
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

import static com.example.popping.dto.CommentResponse.mapToResponse;

@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

    public static final int COMMENTS_SIZE = 100;
    private final PostService postService;
    private final UserService userService;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;

    public Long createMemberComment(Long postId, MemberCommentCreateRequest dto, UserPrincipal userPrincipal, Long parentId) {
        Post post = postService.getPost(postId);
        Comment parent = getParentComment(parentId);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        Comment comment = dto.toEntity(user, post, parent);
        commentRepository.save(comment);

        post.increaseCommentCount();
        return comment.getId();
    }

    public Long createGuestComment(Long postId, GuestCommentCreateRequest dto, Long parentId) {
        Post post = postService.getPost(postId);
        Comment parent = getParentComment(parentId);

        String hashedPassword = passwordEncoder.encode(dto.getGuestPassword());
        Comment comment = dto.toEntity(post, parent, hashedPassword);
        commentRepository.save(comment);

        post.increaseCommentCount();
        return comment.getId();
    }

    public void deleteComment(Long commentId, UserPrincipal userPrincipal) {
        Comment comment = getComment(commentId);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        if (!comment.isAuthor(user)) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "댓글 작성자가 아닙니다." + user.getLoginId());
        }

        comment.getPost().decreaseCommentCount();
        commentRepository.delete(comment);
    }

    public void deleteCommentAsGuest(Long commentId, String password) {
        Comment comment = getComment(commentId);

        if (comment.getAuthor() != null) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "회원이 작성한 댓글은 비밀번호로 삭제할 수 없습니다.");
        }

        if (!passwordEncoder.matches(password, comment.getGuestPasswordHash())) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "비밀번호가 일치하지 않습니다.");
        }

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

        int totalPages = (int) Math.ceil((double) totalComments / COMMENTS_SIZE);
        boolean hasNext = page < totalPages - 1;
        boolean hasPrevious = page > 0;

        List<Object[]> pagedCommentTree = commentRepository.findPagedCommentTree(postId, COMMENTS_SIZE, page * COMMENTS_SIZE);
        List<CommentResponse> commentResponses = buildCommentTree(pagedCommentTree);

        return CommentPageResponse.builder()
                .comments(commentResponses)
                .totalComments(totalComments)
                .currentPage(page)
                .totalPages(totalPages)
                .hasNext(hasNext)
                .hasPrevious(hasPrevious)
                .build();
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByPostId(Long postId) {
        Post post = postService.getPost(postId);

        List<Comment> parentComments = commentRepository.findByPostAndParentIsNullOrderByIdAsc(post);

        return parentComments.stream()
                .map(CommentResponse::from)
                .toList();
    }

    public Comment getComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new CustomAppException(ErrorType.COMMENT_NOT_FOUND, "해당 댓글이 존재하지 않습니다: " + commentId));
    }

    private Comment getParentComment(Long parentId) {
        if (parentId == null) return null;
        return getComment(parentId);
    }

    public Long getParentCommentId(Long commentId) {
        Comment comment = getComment(commentId);

        return comment.getParent() != null ? comment.getParent().getId() : null;
    }

    public Long getPreviousCommentId(Long postId, Long commentId) {
        Post post = postService.getPost(postId);

        List<Comment> parentComments = commentRepository.findByPostAndParentIsNullOrderByIdAsc(post);

        Long previousId = null;
        for (Comment comment : parentComments) {
            if (comment.getId().equals(commentId)) {
                return previousId;
            }
            previousId = comment.getId();
        }

        return null;
    }

    public List<CommentResponse> buildCommentTree(List<Object[]> rows) {
        Set<Long> userIds = rows.stream()
                .map(row -> row[6])
                .filter(Objects::nonNull)
                .map(userId -> (Long) userId)
                .collect(Collectors.toSet());

        Map<Long, String> userIdToNickname = userService
                .getUserIdToNicknameMap(userIds);

        Map<Long, CommentResponse> map = new LinkedHashMap<>();
        List<CommentResponse> result = new ArrayList<>();

        for (Object[] row : rows) {
            CommentResponse dto = mapToResponse(row, userIdToNickname);
            map.put(dto.getId(), dto);

            if (dto.getParentId() == null) {
                result.add(dto);
            } else {
                CommentResponse parent = map.get(dto.getParentId());
                if (parent != null) {
                    parent.getChildren().add(dto);
                } else {
                    result.add(dto); // Orphaned reply, treat as top-level
                }
            }
        }

        return result;
    }
}
