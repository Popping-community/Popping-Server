package com.example.popping.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Board extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String description;

    @Column(unique = true)
    private String slug;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User createdBy;

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public boolean isCreatedBy(User user) {
        return createdBy != null && user != null && createdBy.getId().equals(user.getId());
    }

}
