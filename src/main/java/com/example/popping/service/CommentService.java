package com.example.popping.service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.CommentResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.repository.CommentRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

    private final PostService postService;
    private final CommentRepository commentRepository;
    private final PasswordEncoder passwordEncoder;

    public Long createMemberComment(Long postId, MemberCommentCreateRequest dto, UserPrincipal user, Long parentId) {
        Post post = postService.getPostEntity(postId);
        Comment parent = getParentComment(parentId);

        Comment comment = dto.toEntity(user.getUser(), post, parent);
        commentRepository.save(comment);
        return comment.getId();
    }

    public Long createGuestComment(Long postId, GuestCommentCreateRequest dto, Long parentId) {
        Post post = postService.getPostEntity(postId);
        Comment parent = getParentComment(parentId);

        String hashedPassword = passwordEncoder.encode(dto.getGuestPassword());
        Comment comment = dto.toEntity(post, parent, hashedPassword);
        commentRepository.save(comment);
        return comment.getId();
    }

    public void deleteComment(Long commentId, UserPrincipal user) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("댓글이 존재하지 않습니다."));

        if (!comment.isAuthor(user.getUser())) {
            throw new AccessDeniedException("작성자가 아닙니다.");
        }

        commentRepository.delete(comment);
    }

    public void deleteCommentAsGuest(Long commentId, String password) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("댓글이 존재하지 않습니다."));

        if (comment.getAuthor() != null) {
            throw new AccessDeniedException("회원 댓글은 비회원이 삭제할 수 없습니다.");
        }

        if (!passwordEncoder.matches(password, comment.getGuestPasswordHash())) {
            throw new AccessDeniedException("비밀번호가 일치하지 않습니다.");
        }

        commentRepository.delete(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByPostId(Long postId) {
        Post post = postService.getPostEntity(postId);

        List<Comment> parentComments = commentRepository.findByPostAndParentIsNullOrderByIdAsc(post);

        return parentComments.stream()
                .map(CommentResponse::from)
                .toList();
    }

    private Comment getParentComment(Long parentId) {
        if (parentId == null) return null;
        return commentRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("부모 댓글이 존재하지 않습니다."));
    }

    public Long getParentCommentId(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("댓글이 존재하지 않습니다."));

        return comment.getParent() != null ? comment.getParent().getId() : null;
    }

    public Long getPreviousCommentId(Long postId, Long commentId) {
        Post post = postService.getPostEntity(postId);

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
