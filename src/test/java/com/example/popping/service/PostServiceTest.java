package com.example.popping.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.PostRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock BoardService boardService;
    @Mock ImageService imageService;
    @Mock UserService userService;
    @Mock ViewCountService viewCountService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PostRepository postRepository;

    @InjectMocks PostService postService;

    @Test
    @DisplayName("회원 게시글 생성: Post를 올바르게 생성하고 저장 후 이미지 링크를 수행한다")
    void createMemberPost_success() {

        // given
        String slug = "board-1";
        MemberPostCreateRequest dto =
                new MemberPostCreateRequest("title", "content<img/>");

        UserPrincipal principal = principal(1L);

        Board board = mock(Board.class);
        when(boardService.getBoard(slug)).thenReturn(board);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Post saved = postWithId(100L);
        when(postRepository.save(any(Post.class))).thenReturn(saved);

        // when
        Long postId = postService.createMemberPost(slug, dto, principal);

        // then
        assertEquals(100L, postId);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());

        Post captured = captor.getValue();
        assertEquals("title", captured.getTitle());
        assertEquals("content<img/>", captured.getContent());
        assertSame(user, captured.getAuthor());
        assertSame(board, captured.getBoard());

        verify(imageService).linkToPostAndMakePermanent(dto.content(), saved);
    }

    @Test
    @DisplayName("비회원 게시글 생성: 비밀번호를 encode하고 Post를 생성 후 이미지 링크를 수행한다")
    void createGuestPost_success() {

        // given
        String slug = "board-1";
        GuestPostCreateRequest dto =
                new GuestPostCreateRequest("title", "content<img/>", "guestNick", "1234");

        Board board = mock(Board.class);
        when(boardService.getBoard(slug)).thenReturn(board);

        when(passwordEncoder.encode("1234")).thenReturn("ENC");

        Post saved = postWithId(200L);
        when(postRepository.save(any(Post.class))).thenReturn(saved);

        // when
        Long postId = postService.createGuestPost(slug, dto);

        // then
        assertEquals(200L, postId);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());

        Post captured = captor.getValue();
        assertEquals("title", captured.getTitle());
        assertEquals("content<img/>", captured.getContent());
        assertEquals("guestNick", captured.getGuestNickname());
        assertEquals("ENC", captured.getGuestPasswordHash());
        assertSame(board, captured.getBoard());

        verify(passwordEncoder).encode("1234");
        verify(imageService).linkToPostAndMakePermanent(dto.content(), saved);
    }

    @Test
    @DisplayName("회원 게시글 수정: 작성자면 예외 없이 완료되고 이미지 링크를 수행한다")
    void updatePost_success_owner() {

        // given
        Long postId = 10L;
        MemberPostUpdateRequest dto = new MemberPostUpdateRequest("newTitle", "newContent<img/>");
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(true);

        // when
        postService.updatePost(postId, dto, principal);

        // then
        verify(post).updateAsMember("newTitle", "newContent<img/>");
        verify(imageService).linkToPostAndMakePermanent(dto.content(), post);
    }

    @Test
    @DisplayName("회원 게시글 수정: 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void updatePost_fail_notOwner() {

        // given
        Long postId = 10L;
        MemberPostUpdateRequest dto = new MemberPostUpdateRequest("newTitle", "newContent");
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getLoginId()).thenReturn("login");

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.updatePost(postId, dto, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(post, never()).updateAsMember(anyString(), anyString());
        verify(imageService, never()).linkToPostAndMakePermanent(anyString(), any());
    }

    @Test
    @DisplayName("비회원 게시글 수정: 비회원 글이면 예외 없이 완료되고 비밀번호를 encode 한 뒤 이미지 링크를 수행한다")
    void updatePostAsGuest_success_guestPost() {

        // given
        Long postId = 20L;
        GuestPostUpdateRequest dto = new GuestPostUpdateRequest(
                "newTitle", "newContent<img/>", "newNick", "9999"
        );

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(true);

        when(passwordEncoder.encode("9999")).thenReturn("ENC2");

        // when
        postService.updatePostAsGuest(postId, dto);

        // then
        verify(post).updateAsGuest("newTitle", "newContent<img/>", "newNick");
        verify(passwordEncoder).encode("9999");
        verify(post).changeGuestPasswordHash("ENC2");
        verify(imageService).linkToPostAndMakePermanent(dto.content(), post);
    }

    @Test
    @DisplayName("비회원 게시글 수정: 회원 글이면 ACCESS_DENIED 예외를 던진다")
    void updatePostAsGuest_fail_memberPost() {

        // given
        Long postId = 20L;
        GuestPostUpdateRequest dto = new GuestPostUpdateRequest(
                "newTitle", "newContent", "newNick", "9999"
        );

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.updatePostAsGuest(postId, dto)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(post, never()).updateAsGuest(anyString(), anyString(), anyString());
        verify(post, never()).changeGuestPasswordHash(anyString());
        verify(passwordEncoder, never()).encode(anyString());
        verify(imageService, never()).linkToPostAndMakePermanent(anyString(), any());
    }

    @Test
    @DisplayName("회원 게시글 삭제: 작성자면 이미지 삭제 후 게시글을 삭제한다")
    void deletePost_success_owner() {

        // given
        Long postId = 30L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(true);

        // when
        postService.deletePost(postId, principal);

        // then
        verify(imageService).deleteImages(post);
        verify(postRepository).delete(post);
    }

    @Test
    @DisplayName("회원 게시글 삭제: 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void deletePost_fail_notOwner() {

        // given
        Long postId = 30L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getLoginId()).thenReturn("login");

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.deletePost(postId, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(imageService, never()).deleteImages(any());
        verify(postRepository, never()).delete(any());
    }

    @Test
    @DisplayName("비회원 게시글 삭제: 비회원 글이면 이미지 삭제 후 게시글을 삭제한다")
    void deletePostAsGuest_success_guestPost() {

        // given
        Long postId = 40L;

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(true);

        // when
        postService.deletePostAsGuest(postId);

        // then
        verify(imageService).deleteImages(post);
        verify(postRepository).delete(post);
    }

    @Test
    @DisplayName("비회원 게시글 삭제: 회원 글이면 ACCESS_DENIED 예외를 던진다")
    void deletePostAsGuest_fail_memberPost() {

        // given
        Long postId = 40L;

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.deletePostAsGuest(postId)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(imageService, never()).deleteImages(any());
        verify(postRepository, never()).delete(any());
    }

    @Test
    @DisplayName("게시글 조회: 조회수 증가 후 PostResponse로 매핑된다")
    void getPostResponse_success_mapping() {

        // given
        Long postId = 50L;

        User author = userForMapping(1L, "작성자닉");
        Board board = mock(Board.class);

        Post post = mock(Post.class);
        when(post.getId()).thenReturn(postId);
        when(post.getTitle()).thenReturn("제목");
        when(post.getContent()).thenReturn("내용");
        when(post.getAuthor()).thenReturn(author);
        when(post.getBoard()).thenReturn(board);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        // when
        PostResponse res = postService.getPostResponse(postId);

        // then
        verify(viewCountService).increaseView(postId);
        assertNotNull(res);
        verify(postRepository).findById(postId);
    }

    @Test
    @DisplayName("회원 게시글 편집 조회: 작성자면 PostResponse로 매핑된다")
    void getMemberPostForEdit_success_owner() {

        // given
        Long postId = 60L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        User author = userForMapping(1L, "작성자닉");
        Board board = mock(Board.class);

        Post post = mock(Post.class);
        when(post.getId()).thenReturn(postId);
        when(post.getTitle()).thenReturn("제목");
        when(post.getContent()).thenReturn("내용");
        when(post.getAuthor()).thenReturn(author);
        when(post.getBoard()).thenReturn(board);

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(true);

        // when
        PostResponse res = postService.getMemberPostForEdit(postId, principal);

        // then
        assertNotNull(res);
        verify(post).isAuthor(user);
    }

    @Test
    @DisplayName("회원 게시글 편집 조회: 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void getMemberPostForEdit_fail_notOwner() {

        // given
        Long postId = 60L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getLoginId()).thenReturn("login");

        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isAuthor(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.getMemberPostForEdit(postId, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
    }

    @Test
    @DisplayName("게시판별 게시글 조회: PostResponse 리스트로 변환된다")
    void getPostsByBoardSlug_success_mapping() {

        // given
        String slug = "board-1";
        Board board = mock(Board.class);
        when(boardService.getBoard(slug)).thenReturn(board);

        User a1 = userForMapping(1L, "u1");
        User a2 = userForMapping(2L, "u2");

        Post p1 = mock(Post.class);
        when(p1.getId()).thenReturn(1L);
        when(p1.getTitle()).thenReturn("t1");
        when(p1.getContent()).thenReturn("c1");
        when(p1.getAuthor()).thenReturn(a1);
        when(p1.getBoard()).thenReturn(board);

        Post p2 = mock(Post.class);
        when(p2.getId()).thenReturn(2L);
        when(p2.getTitle()).thenReturn("t2");
        when(p2.getContent()).thenReturn("c2");
        when(p2.getAuthor()).thenReturn(a2);
        when(p2.getBoard()).thenReturn(board);

        when(postRepository.findAllByBoard(board)).thenReturn(List.of(p1, p2));

        // when
        List<PostResponse> list = postService.getPostsByBoardSlug(slug);

        // then
        assertEquals(2, list.size());
        verify(postRepository).findAllByBoard(board);
    }

    @Test
    @DisplayName("비회원 비밀번호 검증: matches 결과를 반환한다")
    void verifyGuestPassword_success() {

        // given
        Long postId = 70L;
        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(true);
        when(post.getGuestPasswordHash()).thenReturn("HASH");

        when(passwordEncoder.matches("raw", "HASH")).thenReturn(true);

        // when
        boolean ok = postService.verifyGuestPassword(postId, "raw");

        // then
        assertTrue(ok);
        verify(passwordEncoder).matches("raw", "HASH");
    }

    @Test
    @DisplayName("비회원 비밀번호 검증: 회원 글이면 ACCESS_DENIED 예외를 던진다")
    void verifyGuestPassword_fail_memberPost() {

        // given
        Long postId = 70L;
        Post post = mock(Post.class);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(post.isGuest()).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.verifyGuestPassword(postId, "raw")
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("게시글 조회(getPost): 없으면 POST_NOT_FOUND 예외를 던진다")
    void getPost_fail_notFound() {

        // given
        when(postRepository.findById(999L)).thenReturn(Optional.empty());

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> postService.getPost(999L)
        );

        // then
        assertEquals(ErrorType.POST_NOT_FOUND, ex.getErrorType());
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    private User userAuthOnly() {
        return mock(User.class);
    }

    private User userForMapping(Long id, String nickname) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getNickname()).thenReturn(nickname);
        return u;
    }

    private Post postWithId(Long id) {
        Post p = mock(Post.class);
        when(p.getId()).thenReturn(id);
        return p;
    }
}

