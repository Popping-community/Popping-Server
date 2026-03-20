package com.example.popping.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.popping.domain.Board;

public interface BoardRepository extends JpaRepository<Board, Long> {

    @EntityGraph(attributePaths = "createdBy")
    Optional<Board> findBySlug(String slug);

    @Query(
        value = "SELECT b FROM Board b JOIN FETCH b.createdBy",
        countQuery = "SELECT COUNT(b) FROM Board b"
    )
    Page<Board> findAll(Pageable pageable);
}
