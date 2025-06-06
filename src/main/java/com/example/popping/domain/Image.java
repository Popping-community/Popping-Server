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
public class Image extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String imageUrl;

    @Enumerated(EnumType.STRING)
    private ImageStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;

    public void updatePostAndStatus(Post post) {
        this.post = post;
        this.status = ImageStatus.PERMANENT;
    }

    @Builder
    public Image(String imageUrl) {
        this.imageUrl = imageUrl;
        this.status = ImageStatus.TEMP;
    }
}
