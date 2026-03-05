package com.example.popping.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    public Long createMemberPost(String slug,
                                 MemberPostCreateRequest dto,
                                 UserPrincipal principal) {

        Board board = getBoard(slug);
        User user = getUser(principal);

        Post post = Post.createMemberPost(dto.title(), dto.content(), user, board);
        post = postRepository.save(post);

        linkImages(dto.content(), post);

        return post.getId();
    }

    public Long createGuestPost(String slug, GuestPostCreateRequest dto) {

        Board board = getBoard(slug);

        String encodedPassword = passwordEncoder.encode(dto.guestPassword());

        Post post = Post.createGuestPost(
                dto.title(),
                dto.content(),
                dto.guestNickname(),
                encodedPassword,
                board
        );

        post = postRepository.save(post);

        linkImages(dto.content(), post);

        return post.getId();
    }

    public void updatePost(Long postId, MemberPostUpdateRequest dto, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        post.updateAsMember(dto.title(), dto.content());

        linkImages(dto.content(), post);
    }

    public void updatePostAsGuest(Long postId, GuestPostUpdateRequest dto) {

        Post post = getPost(postId);
        validateGuestPost(post);

        post.updateAsGuest(dto.title(), dto.content(), dto.guestNickname());

        post.changeGuestPasswordHash(passwordEncoder.encode(dto.guestPassword()));

        linkImages(dto.content(), post);
    }

    public void deletePost(Long postId, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        deleteImages(post);
        postRepository.delete(post);
    }

    public void deletePostAsGuest(Long postId) {

        Post post = getPost(postId);
        validateGuestPost(post);

        deleteImages(post);
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
    public PostResponse getMemberPostForEdit(Long postId, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public PostPageResponse getPostPage(String slug, int page, int size) {

        Board board = getBoard(slug);

        Page<PostResponse> postPage = postRepository.findAllByBoard(board, PageRequest.of(page, size))
                .map(PostResponse::from);

        return new PostPageResponse(
                postPage.getContent(),
                (int) postPage.getTotalElements(),
                postPage.getNumber(),
                postPage.getTotalPages(),
                postPage.hasNext(),
                postPage.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public boolean verifyGuestPassword(Long postId, String rawPassword) {

        Post post = getPost(postId);
        validateGuestPost(post);

        return passwordEncoder.matches(rawPassword, post.getGuestPasswordHash());
    }

    @Transactional(readOnly = true)
    public Post getPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new CustomAppException(
                        ErrorType.POST_NOT_FOUND,
                        "해당 게시글이 존재하지 않습니다: " + postId
                ));
    }

    private Board getBoard(String slug) {
        return boardService.getBoard(slug);
    }

    private User getUser(UserPrincipal principal) {
        return userService.getLoginUserById(principal.getUserId());
    }

    private void validateAuthor(Post post, User user) {
        if (!post.isAuthor(user)) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "작성자가 아닙니다: " + user.getLoginId());
        }
    }

    private void validateGuestPost(Post post) {
        if (!post.isGuest()) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "회원 게시글은 비밀번호로 수정/삭제할 수 없습니다.");
        }
    }

    private void linkImages(String content, Post post) {
        imageService.linkToPostAndMakePermanent(content, post);
    }

    private void deleteImages(Post post) {
        imageService.deleteImages(post);
    }
}

