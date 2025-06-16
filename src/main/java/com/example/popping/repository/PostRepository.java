package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;

public interface PostRepository extends JpaRepository<Post, Long> {
    List<Post> findAllByBoard(Board board);

    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void increaseViewCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + :delta WHERE p.id = :postId")
    void updateLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Post p SET p.dislikeCount = p.dislikeCount + :delta WHERE p.id = :postId")
    void updateDislikeCount(@Param("postId") Long postId, @Param("delta") int delta);
}
