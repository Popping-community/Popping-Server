package com.example.popping.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.popping.domain.Board;

public interface BoardRepository extends JpaRepository<Board, Long> {

    Optional<Board> findBySlug(String slug);

    Page<Board> findAll(Pageable pageable);
}
