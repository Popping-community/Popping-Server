package com.example.popping.domain;

import java.util.Objects;

import org.hibernate.proxy.HibernateProxy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50, unique = true)
    private String loginId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 30, unique = true)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    private User(String loginId, String passwordHash, String nickname, UserRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
    }

    public static User create(String loginId, String passwordHash, String nickname, UserRole role) {
        if (loginId == null || loginId.isBlank()) throw new IllegalArgumentException("loginId는 필수입니다.");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("passwordHash는 필수입니다.");
        if (nickname == null || nickname.isBlank()) throw new IllegalArgumentException("nickname은 필수입니다.");
        if (role == null) throw new IllegalArgumentException("role은 필수입니다.");

        return new User(loginId, passwordHash, nickname, role);
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;

        // Hibernate 프록시 고려
        Class<?> thisClass = (this instanceof HibernateProxy hp)
                ? hp.getHibernateLazyInitializer().getPersistentClass()
                : getClass();

        Class<?> otherClass = (o instanceof HibernateProxy hp)
                ? hp.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();

        if (!thisClass.equals(otherClass)) return false;

        User other = (User) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hashCode(id);
    }
}
