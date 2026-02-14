package com.example.popping.service;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.PostRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

    private final BoardService boardService;
    private final ImageService imageService;
    private final UserService userService;
    private final ViewCountService viewCountService;
    private final PasswordEncoder passwordEncoder;
    private final PostRepository postRepository;

    public Long createMemberPost(String slug, MemberPostCreateRequest dto, UserPrincipal userPrincipal) {
        Board board = boardService.getBoard(slug);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        Post post = dto.toEntity(user, board);
        postRepository.save(post);

        imageService.linkToPostAndMakePermanent(dto.getContent(), post);

        return post.getId();
    }

    public Long createGuestPost(String slug, GuestPostCreateRequest dto) {
        Board board = boardService.getBoard(slug);

        Post post = dto.toEntity(board, passwordEncoder.encode(dto.getGuestPassword()));
        postRepository.save(post);

        imageService.linkToPostAndMakePermanent(dto.getContent(), post);

        return post.getId();
    }

    public void updatePost(Long postId, MemberPostUpdateRequest dto, UserPrincipal userPrincipal) {
        Post post = getPost(postId);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateAuthor(post, user);

        post.memberUpdate(dto.getTitle(), dto.getContent());

        imageService.linkToPostAndMakePermanent(dto.getContent(), post);
    }

    public void updatePostAsGuest(Long postId, GuestPostUpdateRequest dto) {
        Post post = getPost(postId);

        post.guestUpdate(dto.getTitle(), dto.getContent(), dto.getGuestNickname(), passwordEncoder.encode(dto.getGuestPassword()));

        imageService.linkToPostAndMakePermanent(dto.getContent(), post);
    }

    public void deletePost(Long postId, UserPrincipal userPrincipal) {
        Post post = getPost(postId);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateAuthor(post, user);

        imageService.deleteImages(post);

        postRepository.delete(post);
    }

    public void deletePostAsGuest(Long postId) {
        Post post = getPost(postId);

        imageService.deleteImages(post);

        postRepository.delete(post);
    }

    public void updateLikeCount(Long targetId, int delta) {
        postRepository.updateLikeCount(targetId, delta);
    }

    public void updateDislikeCount(Long targetId, int delta) {
        postRepository.updateDislikeCount(targetId, delta);
    }

    @Transactional(readOnly = true)
    public PostResponse getPostResponse(Long postId) {
        viewCountService.increaseView(postId);
        Post post = getPost(postId);

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public Post getPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new CustomAppException(ErrorType.POST_NOT_FOUND,
                        "해당 게시글이 존재하지 않습니다: " + postId));
    }

    @Transactional(readOnly = true)
    public PostResponse getMemberPostForEdit(Long postId, UserPrincipal userPrincipal) {
        Post post = getPost(postId);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateAuthor(post, user);

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public List<PostResponse> getPostsByBoardSlug(String slug) {
        Board board = boardService.getBoard(slug);

        List<Post> posts = postRepository.findAllByBoard(board);

        return posts.stream()
                .map(PostResponse::from)
                .toList();
    }

    private void validateAuthor(Post post, User user) {
        if (!post.isAuthor(user)) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "작성자가 아닙니다: " + user.getLoginId());
        }
    }

    public boolean verifyGuestPassword(Long postId, String password) {
        Post post = getPost(postId);

        if (post.getAuthor() != null) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "회원 게시글은 비밀번호로 삭제할 수 없습니다.");
        }

        return passwordEncoder.matches(password, post.getGuestPasswordHash());
    }
}
