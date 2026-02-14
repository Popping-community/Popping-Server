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

    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    @Modifying
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount + :delta WHERE c.id = :commentId")
    void updateLikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    @Modifying
    @Query("UPDATE Comment c SET c.dislikeCount = c.dislikeCount + :delta WHERE c.id = :commentId")
    void updateDislikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    @Query(value = """
            WITH RECURSIVE comment_tree AS (
                SELECT 
                    c.id,
                    c.parent_id,
                    c.post_id,
                    c.content,
                    c.depth,
                    c.created_at,
                    c.user_id,
                    c.guest_nickname,
                    c.like_count,
                    c.dislike_count,
                    CAST(LPAD(c.id, 10, '0') AS CHAR(1000)) AS path
                FROM comment c
                WHERE c.post_id = :postId AND c.parent_id IS NULL

                UNION ALL

                SELECT 
                    c.id,
                    c.parent_id,
                    c.post_id,
                    c.content,
                    c.depth,
                    c.created_at,
                    c.user_id,
                    c.guest_nickname,
                    c.like_count,
                    c.dislike_count,
                    CAST(CONCAT(ct.path, '-', LPAD(c.id, 10, '0')) AS CHAR(1000)) AS path
                FROM comment c
                JOIN comment_tree ct ON c.parent_id = ct.id
            )
            SELECT 
                id,
                parent_id,
                post_id,
                content,
                depth,
                created_at,
                user_id,
                guest_nickname,
                like_count,
                dislike_count
            FROM comment_tree
            ORDER BY path
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findPagedCommentTree(
            @Param("postId") Long postId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
