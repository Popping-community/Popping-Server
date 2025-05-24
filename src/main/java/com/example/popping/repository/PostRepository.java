package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;

public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findAllByBoard(Board board);
}
