package com.example.popping.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;

public interface LikeRepository extends JpaRepository<Like, Long> {

    @Query(value = """
            SELECT l.target_id AS targetId,
                   MAX(CASE WHEN l.type = 'LIKE'    THEN 1 ELSE 0 END) AS likedByMe,
                   MAX(CASE WHEN l.type = 'DISLIKE' THEN 1 ELSE 0 END) AS dislikedByMe
            FROM likes l
            WHERE l.user_id = :userId
              AND l.target_type = :targetType
              AND l.target_id IN (:ids)
            GROUP BY l.target_id
            """, nativeQuery = true)
    List<MyReactionView> findReactionForMember(
            @Param("ids") Collection<Long> ids,
            @Param("targetType") String targetType,
            @Param("userId") Long userId);

    @Query(value = """
            SELECT l.target_id AS targetId,
                   MAX(CASE WHEN l.type = 'LIKE'    THEN 1 ELSE 0 END) AS likedByMe,
                   MAX(CASE WHEN l.type = 'DISLIKE' THEN 1 ELSE 0 END) AS dislikedByMe
            FROM likes l
            WHERE l.guest_identifier = :guestId
              AND l.target_type = :targetType
              AND l.target_id IN (:ids)
            GROUP BY l.target_id
            """, nativeQuery = true)
    List<MyReactionView> findReactionForGuest(
            @Param("ids") Collection<Long> ids,
            @Param("targetType") String targetType,
            @Param("guestId") String guestId);

    long countByTargetIdAndTargetTypeAndTypeAndUser_Id(
            Long targetId, Like.TargetType targetType, Like.Type type, Long userId);

    /**
     * Inserts a like row, treating an existing row as a no-op.
     *
     * <p>Uses {@code ON DUPLICATE KEY UPDATE id = id} rather than {@code INSERT IGNORE}:
     * {@code INSERT IGNORE} downgrades every error to a warning (truncation, NOT NULL,
     * FK violations), not just duplicate keys.
     *
     * <p><b>Returns affected rows: 1 when inserted, 0 when the row already existed.</b>
     * The zero-on-duplicate contract depends on the JDBC URL setting
     * {@code useAffectedRows=true}. Without it Connector/J enables {@code CLIENT_FOUND_ROWS},
     * which reports <em>found</em> rows instead of <em>changed</em> rows, so a duplicate
     * would return 1 and callers would double-count. See {@code LikeConcurrencyTest}.
     */
    @Modifying
    @Query(value = """
    insert into likes (target_type, target_id, type, user_id, guest_identifier)
    values (:targetType, :targetId, :type, :userId, :guestIdentifier)
    on duplicate key update id = id
    """, nativeQuery = true)
    int upsertLike(
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId,
            @Param("type") String type,
            @Param("userId") Long userId,
            @Param("guestIdentifier") String guestIdentifier
    );

    @Modifying
    @Query("""
    delete from Like l
    where l.targetType = :targetType
      and l.targetId = :targetId
      and l.type = :type
      and (
            (:user is not null and l.user = :user)
         or (:guestIdentifier is not null and l.guestIdentifier = :guestIdentifier)
      )
    """)
    int deleteByActor(
            @Param("targetType") Like.TargetType targetType,
            @Param("targetId") Long targetId,
            @Param("type") Like.Type type,
            @Param("user") User user,
            @Param("guestIdentifier") String guestIdentifier
    );
}
