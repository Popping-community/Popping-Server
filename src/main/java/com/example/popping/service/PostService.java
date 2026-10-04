package com.example.popping.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.domain.*;
import com.example.popping.dto.*;
import com.example.popping.event.CacheEvictEvent;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;
import com.example.popping.repository.MyReactionView;

@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

    private static final String POST_DETAIL_CACHE = CacheConfig.POST_DETAIL_CACHE;

    private final BoardService boardService;
    private final ImageService imageService;
    private final UserService userService;
    private final ViewCountService viewCountService;
    private final PasswordEncoder guestPasswordEncoder;
    private final PostRepository postRepository;
    private final LikeRepository likeRepository;
    private final CacheManager cacheManager;
    private final TransactionTemplate readOnlyTx;
    private final ApplicationEventPublisher eventPublisher;

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
        evictPostDetailCache(postId);
    }

    public void updatePostAsGuest(Long postId, GuestPostUpdateRequest dto) {

        Post post = getPost(postId);
        validateGuestPost(post);

        post.updateAsGuest(dto.title(), dto.content(), dto.guestNickname());

        post.changeGuestPasswordHash(guestPasswordEncoder.encode(dto.guestPassword()));

        linkImages(dto.content(), post);
        evictPostDetailCache(postId);
    }

    public void deletePost(Long postId, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        deleteImages(post);
        postRepository.delete(post);
        evictPostDetailCache(postId);
    }

    public void deletePostAsGuest(Long postId) {

        Post post = getPost(postId);
        validateGuestPost(post);

        deleteImages(post);
        postRepository.delete(post);
        evictPostDetailCache(postId);
    }

    public void updateLikeCount(Long targetId, int delta) {
        postRepository.updateLikeCount(targetId, delta);
    }

    public void updateDislikeCount(Long targetId, int delta) {
        postRepository.updateDislikeCount(targetId, delta);
    }

    public LikeCountView getLikeCounts(Long targetId) {
        LikeCountView counts = postRepository.findLikeCountsById(targetId);
        if (counts == null) {
            throw new CustomAppException(ErrorType.POST_NOT_FOUND);
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public PostResponse getPostResponse(Long postId, UserPrincipal principal, String guestIdentifier) {
        PostResponse base = getPostDetailFromCache(postId);
        viewCountService.increaseView(postId);
        base = mergePendingViewCount(base);

        return mergePostReactions(base, principal, guestIdentifier);
    }

    private PostResponse mergePendingViewCount(PostResponse base) {
        long pending = viewCountService.getPendingCount(base.id());
        if (pending == 0) {
            return base;
        }
        return base.withViewCount(base.viewCount() + pending);
    }

    // Detail caching is disabled by default: local eviction cannot reach other JVMs.
    // Opt-in entries may remain stale until local eviction or expiry; DB routing is unchanged.
    private PostResponse getPostDetailFromCache(Long postId) {
        Cache cache = cacheManager.getCache(POST_DETAIL_CACHE);
        if (cache == null) {
            return buildPostDetail(postId);
        }
        try {
            return cache.get(postId, () -> readOnlyTx.execute(
                    status -> buildPostDetail(postId)));
        } catch (Cache.ValueRetrievalException e) {
            if (e.getCause() instanceof CustomAppException appException) {
                throw appException;
            }
            throw e;
        }
    }

    private PostResponse buildPostDetail(Long postId) {
        Post post = getPost(postId);
        return PostResponse.from(post, false, false);
    }

    private PostResponse mergePostReactions(PostResponse base, UserPrincipal principal, String guestIdentifier) {
        if (principal == null && (guestIdentifier == null || guestIdentifier.isBlank())) {
            return base;
        }
        MyReactionView s = getPostReaction(List.of(base.id()), principal, guestIdentifier)
                .get(base.id());
        boolean likedByMe    = s != null && s.getLikedByMe()    == 1;
        boolean dislikedByMe = s != null && s.getDislikedByMe() == 1;
        return base.withReactions(likedByMe, dislikedByMe);
    }

    @Transactional(readOnly = true)
    public PostResponse getMemberPostForEdit(Long postId, UserPrincipal principal) {

        Post post = getPost(postId);
        User user = getUser(principal);

        validateAuthor(post, user);

        return PostResponse.from(post, false, false);
    }

    // Not cached, the first page included: a cached page saved one list query (about
    // 0.55 ms) while another instance kept serving it for up to its TTL after a write.
    @Transactional(readOnly = true)
    public PostPageResponse getPostPage(String slug, int page, int size) {
        return buildPostPage(slug, page, size);
    }

    private PostPageResponse buildPostPage(String slug, int page, int size) {
        Board board = getBoard(slug);
        return buildPostPage(board, page, size);
    }

    private PostPageResponse buildPostPage(Board board, int page, int size) {
        Slice<PostListItemResponse> postPage = postRepository.findPostListByBoard(board, PageRequest.of(page, size));

        return new PostPageResponse(
                postPage.getContent(),
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

    @Transactional(propagation = Propagation.MANDATORY)
    public Post getPostForUpdate(Long postId) {
        return postRepository.findForUpdateById(postId)
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

    private void evictPostDetailCache(Long postId) {
        if (postId == null) return;
        eventPublisher.publishEvent(new CacheEvictEvent(POST_DETAIL_CACHE, postId));
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
