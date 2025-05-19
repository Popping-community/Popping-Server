package com.example.popping.repository;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findAllByBoard(Board board);
}
