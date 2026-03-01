package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Comment;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount + :delta WHERE c.id = :commentId")
    int updateLikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.dislikeCount = c.dislikeCount + :delta WHERE c.id = :commentId")
    int updateDislikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    @Query(value = """
            WITH RECURSIVE comment_tree AS (
                SELECT 
                    c.id AS id,
                    c.parent_id AS parentId,
                    c.post_id AS postId,
                    c.content AS content,
                    c.depth AS depth,
                    c.created_at AS createdAt,
                    c.user_id AS userId,
                    c.guest_nickname AS guestNickname,
                    c.like_count AS likeCount,
                    c.dislike_count AS dislikeCount,
                    CAST(LPAD(c.id, 10, '0') AS CHAR(1000)) AS path
                FROM comment c
                WHERE c.post_id = :postId AND c.parent_id IS NULL

                UNION ALL

                SELECT 
                    c.id AS id,
                    c.parent_id AS parentId,
                    c.post_id AS postId,
                    c.content AS content,
                    c.depth AS depth,
                    c.created_at AS createdAt,
                    c.user_id AS userId,
                    c.guest_nickname AS guestNickname,
                    c.like_count AS likeCount,
                    c.dislike_count AS dislikeCount,
                    CAST(CONCAT(ct.path, '-', LPAD(c.id, 10, '0')) AS CHAR(1000)) AS path
                FROM comment c
                JOIN comment_tree ct ON c.parent_id = ct.id
            )
            SELECT 
                id,
                parentId,
                postId,
                content,
                depth,
                createdAt,
                userId,
                guestNickname,
                likeCount,
                dislikeCount
            FROM comment_tree
            ORDER BY path
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<CommentTreeRowView> findPagedCommentTree(
            @Param("postId") Long postId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
