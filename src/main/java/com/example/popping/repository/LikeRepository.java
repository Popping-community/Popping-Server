package com.example.popping.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;

public interface LikeRepository extends JpaRepository<Like, Long> {
    Optional<Like> findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(Like.TargetType targetType, Long targetId, User user, String guestIdentifier, Like.Type type);

    List<Like> findAllByTargetTypeAndTargetIdInAndUser(Like.TargetType targetType, Collection<Long> targetIds, User user);

    List<Like> findAllByTargetTypeAndTargetIdInAndGuestIdentifier(Like.TargetType targetType, Collection<Long> targetIds, String guestIdentifier);

    long countByTargetIdAndTargetTypeAndTypeAndUser_Id(
            Long targetId, Like.TargetType targetType, Like.Type type, Long userId);

    @Modifying
    @Query(value = """
    insert ignore into likes (guest_identifier, target_id, target_type, type, user_id)
    values (:guestIdentifier, :targetId, :targetType, :type, :userId)
    """, nativeQuery = true)
    int insertIgnore(
            @Param("guestIdentifier") String guestIdentifier,
            @Param("targetId") Long targetId,
            @Param("targetType") String targetType,
            @Param("type") String type,
            @Param("userId") Long userId
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
