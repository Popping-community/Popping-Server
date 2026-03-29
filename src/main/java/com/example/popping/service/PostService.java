package com.example.popping.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.*;
import com.example.popping.dto.*;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.MyReactionView;

@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

    private final BoardService boardService;
    private final ImageService imageService;
    private final UserService userService;
    private final ViewCountService viewCountService;
    private final PasswordEncoder guestPasswordEncoder;
    private final PostRepository postRepository;
    private final LikeRepository likeRepository;

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

        String encodedPassword = guestPasswordEncoder.encode(dto.guestPassword());

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

        post.changeGuestPasswordHash(guestPasswordEncoder.encode(dto.guestPassword()));

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
    public PostResponse getPostResponse(Long postId, UserPrincipal principal, String guestIdentifier) {
        viewCountService.increaseView(postId);
        Post post = getPost(postId);
        boolean likedByMe = false;
        boolean dislikedByMe = false;

        if (principal != null || (guestIdentifier != null && !guestIdentifier.isBlank())) {
            MyReactionView s = getPostReaction(List.of(postId), principal, guestIdentifier)
                    .get(postId);
            likedByMe    = s != null && s.getLikedByMe()    == 1;
            dislikedByMe = s != null && s.getDislikedByMe() == 1;
        }

        return PostResponse.from(post, likedByMe, dislikedByMe);
    }

    @Transactional(readOnly = true)
    public PostResponse getMemberPostForEdit(Long postId, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        return PostResponse.from(post, false, false);
    }

    @Transactional(readOnly = true)
    public PostPageResponse getPostPage(String slug, int page, int size, UserPrincipal principal, String guestIdentifier) {

        Board board = getBoard(slug);

        Slice<PostListItemResponse> postPage = postRepository.findPostListByBoard(board, PageRequest.of(page, size));

        List<Long> postIds = postPage.getContent().stream()
                .map(PostListItemResponse::id)
                .toList();

        Map<Long, MyReactionView> reactionMap = getPostReaction(postIds, principal, guestIdentifier);

        List<PostListItemResponse> posts = postPage.getContent().stream()
                .map(item -> {
                    MyReactionView s = reactionMap.get(item.id());
                    boolean likedByMe    = s != null && s.getLikedByMe()    == 1;
                    boolean dislikedByMe = s != null && s.getDislikedByMe() == 1;
                    return item.withReactions(likedByMe, dislikedByMe);
                })
                .toList();

        return new PostPageResponse(
                posts,
                postPage.getNumber(),
                postPage.hasNext(),
                postPage.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public boolean verifyGuestPassword(Long postId, String rawPassword) {

        Post post = getPost(postId);
        validateGuestPost(post);

        return guestPasswordEncoder.matches(rawPassword, post.getGuestPasswordHash());
    }

    @Transactional(readOnly = true)
    public Post getPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new CustomAppException(
                        ErrorType.POST_NOT_FOUND,
                        "해당 게시글이 존재하지 않습니다: " + postId
                ));
    }

    private Map<Long, MyReactionView> getPostReaction(List<Long> postIds,
                                                               UserPrincipal principal,
                                                               String guestIdentifier) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyMap();

        String targetType = Like.TargetType.POST.name();
        List<MyReactionView> myReactionViews;
        if (principal != null) {
            myReactionViews = likeRepository.findReactionForMember(postIds, targetType, principal.getUserId());
        } else if (guestIdentifier != null && !guestIdentifier.isBlank()) {
            myReactionViews = likeRepository.findReactionForGuest(postIds, targetType, guestIdentifier);
        } else {
            return Collections.emptyMap();
        }

        return myReactionViews.stream()
                .collect(Collectors.toMap(MyReactionView::getTargetId, s -> s));
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
