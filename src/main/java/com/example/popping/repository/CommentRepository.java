package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByPostAndParentIsNullOrderByIdAsc(Post post);

    @Modifying
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount + :delta WHERE c.id = :commentId")
    void updateLikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Comment c SET c.dislikeCount = c.dislikeCount + :delta WHERE c.id = :commentId")
    void updateDislikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);
}
