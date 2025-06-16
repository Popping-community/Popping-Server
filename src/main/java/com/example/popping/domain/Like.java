package com.example.popping.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    public enum TargetType {
        POST, COMMENT
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String guestIdentifier;

    public boolean isByUser(User user) {
        return user != null && this.user != null && this.user.getId().equals(user.getId());
    }

    public boolean isByGuest(String guestIdentifier) {
        return user == null && this.guestIdentifier != null && this.guestIdentifier.equals(guestIdentifier);
    }
}
