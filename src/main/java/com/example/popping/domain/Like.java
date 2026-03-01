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

    private Like(Type type, TargetType targetType, Long targetId,
                 User user, String guestIdentifier) {
        this.type = type;
        this.targetType = targetType;
        this.targetId = targetId;
        this.user = user;
        this.guestIdentifier = guestIdentifier;
    }

    public static Like createByMember(Type type, TargetType targetType,
                                      Long targetId, User user) {
        validateCommon(type, targetType, targetId);
        if (user == null) {
            throw new IllegalArgumentException("회원 Like는 user가 필수입니다.");
        }
        return new Like(type, targetType, targetId, user, null);
    }

    public static Like createByGuest(Type type, TargetType targetType,
                                     Long targetId, String guestIdentifier) {
        validateCommon(type, targetType, targetId);
        if (guestIdentifier == null || guestIdentifier.isBlank()) {
            throw new IllegalArgumentException("게스트 Like는 guestIdentifier가 필수입니다.");
        }
        return new Like(type, targetType, targetId, null, guestIdentifier);
    }

    private static void validateCommon(Type type, TargetType targetType,
                                       Long targetId) {
        if (type == null) throw new IllegalArgumentException("type은 필수입니다.");
        if (targetType == null) throw new IllegalArgumentException("targetType은 필수입니다.");
        if (targetId == null) throw new IllegalArgumentException("targetId는 필수입니다.");
    }
}