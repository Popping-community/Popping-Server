package com.example.popping.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Board;
import com.example.popping.domain.User;
import com.example.popping.dto.BoardCreateRequest;
import com.example.popping.dto.BoardResponse;
import com.example.popping.dto.BoardUpdateRequest;
import com.example.popping.repository.BoardRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;

    public String createBoard(BoardCreateRequest dto, User user) {
        Board board = dto.toEntity(user);
        boardRepository.save(board);
        return board.getSlug();
    }

    public void updateBoard(String slug, BoardUpdateRequest dto) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        board.update(dto.getName(), dto.getDescription());
    }

    public void deleteBoard(String slug) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        boardRepository.delete(board);
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(String slug) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("해당 게시판이 존재하지 않습니다."));

        return BoardResponse.from(board);
    }

    @Transactional(readOnly = true)
    public List<BoardResponse> getAllBoards() {
        return boardRepository.findAll()
                .stream()
                .map(BoardResponse::from)
                .toList();
    }
}
