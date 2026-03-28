package com.example.popping.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "likes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_like_user_target_type",
                        columnNames = {"user_id", "target_type", "target_id", "type"}),
                @UniqueConstraint(name = "uk_like_guest_target_type",
                        columnNames = {"guest_identifier", "target_type", "target_id", "type"})
        }
)
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public enum Type {
        LIKE, DISLIKE
    }

    public enum TargetType {
        POST, COMMENT
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String guestIdentifier;
}