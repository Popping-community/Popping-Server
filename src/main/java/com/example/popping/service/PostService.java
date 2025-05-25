package com.example.popping.service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.PostRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;
    private final PasswordEncoder passwordEncoder;

    public Long createMemberPost(String slug, MemberPostCreateRequest dto, UserPrincipal user) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        Post post = dto.toEntity(user.getUser(), board);
        postRepository.save(post);

        return post.getId();
    }

    public Long createGuestPost(String slug, GuestPostCreateRequest dto) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        Post post = dto.toEntity(board, passwordEncoder.encode(dto.getGuestPassword()));
        postRepository.save(post);

        return post.getId();
    }

    public void updatePost(Long postId, MemberPostUpdateRequest dto, UserPrincipal user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user.getUser());

        post.memberUpdate(dto.getTitle(), dto.getContent());
    }

    public void updatePostAsGuest(Long postId, GuestPostUpdateRequest dto) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        post.guestUpdate(dto.getTitle(), dto.getContent(), dto.getGuestNickname(), passwordEncoder.encode(dto.getGuestPassword()));
    }

    public void deletePost(Long postId, UserPrincipal user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user.getUser());

        postRepository.delete(post);
    }

    public void deletePostAsGuest(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        postRepository.delete(post);
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public PostResponse getMemberPostForEdit(Long postId, UserPrincipal user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user.getUser());

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public List<PostResponse> getAllPosts() {
        return postRepository.findAll()
                .stream()
                .map(PostResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PostResponse> getPostsByBoardSlug(String slug) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        List<Post> posts = postRepository.findAllByBoard(board);

        return posts.stream()
                .map(PostResponse::from)
                .toList();
    }

    private void validateAuthor(Post post, User user) {
        if (!post.isAuthor(user)) {
            throw new AccessDeniedException("작성자가 아닙니다.");
        }
    }

    public boolean verifyGuestPassword(Long postId, String password) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("게시글이 존재하지 않습니다."));

        if (post.getAuthor() != null) {
            throw new AccessDeniedException("회원 게시글입니다.");
        }

        return passwordEncoder.matches(password, post.getGuestPasswordHash());
    }
}
