package com.example.popping.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.popping.domain.Like;
import com.example.popping.domain.User;

public interface LikeRepository extends JpaRepository<Like, Long> {
    Optional<Like> findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(Like.TargetType targetType, Long targetId, User user, String guestIdentifier, Like.Type type);
}
