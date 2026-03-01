package com.example.popping.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Board;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.BoardCreateRequest;
import com.example.popping.dto.BoardResponse;
import com.example.popping.dto.BoardUpdateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.BoardRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    private final BoardRepository boardRepository;
    private final UserService userService;

    @Transactional
    public String createBoard(BoardCreateRequest req, UserPrincipal principal) {
        User user = getLoginUser(principal);

        Board board = Board.create(
                req.name(),
                req.description(),
                req.slug(),
                user
        );

        board = boardRepository.save(board);

        return board.getSlug();
    }

    @Transactional
    public void updateBoard(String slug, BoardUpdateRequest req, UserPrincipal principal) {
        Board board = getBoardAndValidateOwner(slug, principal);
        board.update(req.name(), req.description());
    }

    @Transactional
    public void deleteBoard(String slug, UserPrincipal principal) {
        Board board = getBoardAndValidateOwner(slug, principal);
        boardRepository.delete(board);
    }

    public BoardResponse getBoardResponse(String slug) {
        return BoardResponse.from(getBoard(slug));
    }

    public BoardResponse getBoardForEdit(String slug, UserPrincipal principal) {
        Board board = getBoardAndValidateOwner(slug, principal);
        return BoardResponse.from(board);
    }

    public List<BoardResponse> getAllBoards() {
        return boardRepository.findAll().stream()
                .map(BoardResponse::from)
                .toList();
    }

    public Board getBoard(String slug) {
        return boardRepository.findBySlug(slug)
                .orElseThrow(() -> new CustomAppException(
                        ErrorType.BOARD_NOT_FOUND,
                        "해당 게시판이 존재하지 않습니다: " + slug
                ));
    }

    private Board getBoardAndValidateOwner(String slug, UserPrincipal principal) {
        Board board = getBoard(slug);
        User user = getLoginUser(principal);
        validateCreatedBy(board, user);
        return board;
    }

    private User getLoginUser(UserPrincipal principal) {
        return userService.getLoginUserById(principal.getUserId());
    }

    private void validateCreatedBy(Board board, User user) {
        if (!board.isCreatedBy(user)) {
            throw new CustomAppException(
                    ErrorType.ACCESS_DENIED,
                    "작성자가 아닙니다: " + user.getLoginId()
            );
        }
    }
}
