package com.example.popping.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.popping.domain.Board;

public interface BoardRepository extends JpaRepository<Board, Long> {
    Optional<Board> findBySlug(String slug);
}
