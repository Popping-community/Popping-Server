package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByPostAndParentIsNullOrderByIdAsc(Post post);
}
