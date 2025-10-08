package com.example.popping.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.popping.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByLoginId(String loginId);

    boolean existsByNickname(String nickname);

    Optional<User> findByLoginId(String loginId);

    @Query("SELECT u.id, u.nickname FROM User u WHERE u.id IN :ids")
    List<Object[]> findUserIdAndNicknameByIds(@Param("ids") Set<Long> ids);
}
