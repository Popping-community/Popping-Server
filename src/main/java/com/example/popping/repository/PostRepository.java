package com.example.popping.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.dto.PostListItemResponse;

public interface PostRepository extends JpaRepository<Post, Long> {

    @EntityGraph(attributePaths = {"author", "board"})
    Optional<Post> findById(Long id);

    @Query(
        value = "SELECT new com.example.popping.dto.PostListItemResponse(" +
                "p.id, p.title, COALESCE(u.nickname, p.guestNickname), u.id, p.guestNickname, " +
                "p.viewCount, p.commentCount, p.likeCount, p.dislikeCount, false, false) " +
                "FROM Post p LEFT JOIN p.author u WHERE p.board = :board",
        countQuery = "SELECT COUNT(p) FROM Post p WHERE p.board = :board"
    )
    Page<PostListItemResponse> findPostListByBoard(@Param("board") Board board, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void increaseViewCount(@Param("postId") Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + :delta WHERE p.id = :postId")
    void updateLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Post p SET p.dislikeCount = p.dislikeCount + :delta WHERE p.id = :postId")
    void updateDislikeCount(@Param("postId") Long postId, @Param("delta") int delta);
}
