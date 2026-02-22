package com.example.popping.domain;

import java.util.Objects;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Board extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, unique = true)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User createdBy;

    private Board(String name, String description, String slug, User createdBy) {
        this.name = name;
        this.description = description;
        this.slug = slug;
        this.createdBy = createdBy;
    }

    public static Board create(String name, String description, String slug, User createdBy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수입니다.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description은 필수입니다.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug는 필수입니다.");
        }
        if (createdBy == null) {
            throw new IllegalArgumentException("createdBy는 필수입니다.");
        }

        return new Board(name, description, slug, createdBy);
    }

    public void update(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name은 필수입니다.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description은 필수입니다.");
        }

        this.name = name;
        this.description = description;
    }

    public boolean isCreatedBy(User user) {
        if (createdBy == null || user == null) {
            return false;
        }
        return Objects.equals(createdBy.getId(), user.getId());
    }
}
