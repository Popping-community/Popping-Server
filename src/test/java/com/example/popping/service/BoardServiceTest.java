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

import com.example.popping.domain.Board;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.BoardCreateRequest;
import com.example.popping.dto.BoardResponse;
import com.example.popping.dto.BoardUpdateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.BoardRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock BoardRepository boardRepository;
    @Mock UserService userService;

    @InjectMocks BoardService boardService;

    @Test
    @DisplayName("게시판 생성: slug를 반환한다")
    void createBoard_success() {

        // given
        BoardCreateRequest req = new BoardCreateRequest("게시판", "설명", "my-board");
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        // when
        String slug = boardService.createBoard(req, principal);

        // then
        assertEquals("my-board", slug);

        ArgumentCaptor<Board> captor = ArgumentCaptor.forClass(Board.class);
        verify(boardRepository).save(captor.capture());

        Board saved = captor.getValue();
        assertEquals("게시판", saved.getName());
        assertEquals("설명", saved.getDescription());
        assertEquals("my-board", saved.getSlug());
        assertSame(user, saved.getCreatedBy());
    }

    @Test
    @DisplayName("게시판 수정: 작성자면 예외 없이 완료된다")
    void updateBoard_success_owner() {

        // given
        String slug = "board-1";
        BoardUpdateRequest req = new BoardUpdateRequest("newName", "newDesc");
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Board board = mock(Board.class);
        when(boardRepository.findBySlug(slug)).thenReturn(Optional.of(board));
        when(board.isCreatedBy(user)).thenReturn(true);

        // when
        boardService.updateBoard(slug, req, principal);

        // then
        verify(board).update("newName", "newDesc");
    }

    @Test
    @DisplayName("게시판 수정: 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void updateBoard_fail_notOwner() {

        // given
        String slug = "board-1";
        BoardUpdateRequest req = new BoardUpdateRequest("newName", "newDesc");
        UserPrincipal principal = principal(1L);

        User user = userWithLoginIdOnly("login");
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Board board = mock(Board.class);
        when(boardRepository.findBySlug(slug)).thenReturn(Optional.of(board));
        when(board.isCreatedBy(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> boardService.updateBoard(slug, req, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(board, never()).update(anyString(), anyString());
    }

    @Test
    @DisplayName("게시판 삭제: 작성자면 예외 없이 완료된다")
    void deleteBoard_success_owner() {

        // given
        String slug = "board-1";
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Board board = mock(Board.class);
        when(boardRepository.findBySlug(slug)).thenReturn(Optional.of(board));
        when(board.isCreatedBy(user)).thenReturn(true);

        // when
        boardService.deleteBoard(slug, principal);

        // then
        verify(boardRepository).delete(board);
    }

    @Test
    @DisplayName("게시판 삭제: 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void deleteBoard_fail_notOwner() {

        // given
        String slug = "board-1";
        UserPrincipal principal = principal(1L);

        User user = userWithLoginIdOnly("login");
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Board board = mock(Board.class);
        when(boardRepository.findBySlug(slug)).thenReturn(Optional.of(board));
        when(board.isCreatedBy(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> boardService.deleteBoard(slug, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(boardRepository, never()).delete(any());
    }

    @Test
    @DisplayName("게시판 조회: BoardResponse가 올바르게 매핑된다")
    void getBoardResponse_success_mapping() {

        // given
        String slug = "board-1";
        User creator = userForMapping(10L, "작성자닉");
        Board board = boardForMapping(slug, "게시판이름", "설명", creator);

        when(boardRepository.findBySlug(slug)).thenReturn(Optional.of(board));

        // when
        BoardResponse res = boardService.getBoardResponse(slug);

        // then
        assertEquals("게시판이름", res.name());
        assertEquals("설명", res.description());
        assertEquals(slug, res.slug());
        assertEquals("작성자닉", res.createdBy());
        assertEquals(10L, res.createdById());
    }

    @Test
    @DisplayName("게시판 조회: 없으면 BOARD_NOT_FOUND 예외를 던진다")
    void getBoard_fail_notFound() {

        // given
        when(boardRepository.findBySlug("nope")).thenReturn(Optional.empty());

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> boardService.getBoard("nope")
        );

        // then
        assertEquals(ErrorType.BOARD_NOT_FOUND, ex.getErrorType());
    }

    @Test
    @DisplayName("전체 게시판 조회: BoardResponse 리스트로 변환된다")
    void getAllBoards_success_mapping() {

        // given
        Board b1 = boardForMapping("s1", "n1", "d1", userForMapping(1L, "u1"));
        Board b2 = boardForMapping("s2", "n2", "d2", userForMapping(2L, "u2"));

        when(boardRepository.findAll()).thenReturn(List.of(b1, b2));

        // when
        List<BoardResponse> list = boardService.getAllBoards();

        // then
        assertEquals(2, list.size());
        assertEquals("n1", list.get(0).name());
        assertEquals("u2", list.get(1).createdBy());
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    private User userAuthOnly() {
        return mock(User.class);
    }

    private User userWithLoginIdOnly(String loginId) {
        User u = mock(User.class);
        when(u.getLoginId()).thenReturn(loginId);
        return u;
    }

    private User userForMapping(Long id, String nickname) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getNickname()).thenReturn(nickname);
        return u;
    }

    private Board boardForMapping(String slug, String name, String desc, User createdBy) {
        Board b = mock(Board.class);
        when(b.getSlug()).thenReturn(slug);
        when(b.getName()).thenReturn(name);
        when(b.getDescription()).thenReturn(desc);
        when(b.getCreatedBy()).thenReturn(createdBy);
        return b;
    }
}
