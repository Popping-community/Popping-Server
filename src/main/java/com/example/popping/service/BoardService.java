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
@Transactional
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final UserService userService;

    public String createBoard(BoardCreateRequest dto, UserPrincipal userPrincipal) {
        User user = userService.getLoginUserById(userPrincipal.getUserId());
        Board board = dto.toEntity(user);
        boardRepository.save(board);
        return board.getSlug();
    }

    public void updateBoard(String slug, BoardUpdateRequest dto, UserPrincipal userPrincipal) {
        Board board = getBoard(slug);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateCreatedBy(board, user);

        board.update(dto.getName(), dto.getDescription());
    }

    public void deleteBoard(String slug, UserPrincipal userPrincipal) {
        Board board = getBoard(slug);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateCreatedBy(board, user);

        boardRepository.delete(board);
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoardResponse(String slug) {
        Board board = getBoard(slug);

        return BoardResponse.from(board);
    }

    @Transactional(readOnly = true)
    public Board getBoard(String slug) {
        return boardRepository.findBySlug(slug)
                .orElseThrow(() -> new CustomAppException(ErrorType.BOARD_NOT_FOUND, "해당 게시판이 존재하지 않습니다: " + slug));
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoardForEdit(String slug, UserPrincipal userPrincipal) {
        Board board = getBoard(slug);

        User user = userService.getLoginUserById(userPrincipal.getUserId());
        validateCreatedBy(board, user);

        return BoardResponse.from(board);
    }

    @Transactional(readOnly = true)
    public List<BoardResponse> getAllBoards() {
        return boardRepository.findAll()
                .stream()
                .map(BoardResponse::from)
                .toList();
    }

    private void validateCreatedBy(Board board, User user) {
        if (!board.isCreatedBy(user)) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED,
                    "작성자가 아닙니다: " + user.getLoginId());
        }
    }
}
