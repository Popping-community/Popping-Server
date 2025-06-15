package com.example.popping.service;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.CommentResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

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
}
