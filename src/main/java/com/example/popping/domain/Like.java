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
                @UniqueConstraint(columnNames = {"user_id", "target_type", "target_id", "type"}),
                @UniqueConstraint(columnNames = {"guestIdentifier", "target_type", "target_id", "type"})
        }
//        indexes = {
//                @Index(name = "idx_like_target_member", columnList = "target_id,user_id,target_type,type"),
//                @Index(name = "idx_like_target_guest",  columnList = "target_id,guest_identifier,target_type,type")
//        }
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