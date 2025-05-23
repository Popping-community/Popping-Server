package com.example.popping.service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.dto.PostCreateRequest;
import com.example.popping.dto.PostResponse;
import com.example.popping.dto.PostUpdateRequest;
import com.example.popping.repository.BoardRepository;
import com.example.popping.repository.PostRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final BoardRepository boardRepository;

    public Long createPost(String slug, PostCreateRequest dto, User user) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        Post post = dto.toEntity(user, board);
        postRepository.save(post);

        return post.getId();
    }

    public void updatePost(Long postId, PostUpdateRequest dto, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user);

        post.update(dto.getTitle(), dto.getContent());
    }

    public void deletePost(Long postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user);

        postRepository.delete(post);
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public PostResponse getPostForEdit(Long postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시글이 존재하지 않습니다."));

        validateAuthor(post, user);

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
        if (!post.getAuthor().equals(user)) {
            throw new AccessDeniedException("작성자가 아닙니다.");
        }
    }

}
