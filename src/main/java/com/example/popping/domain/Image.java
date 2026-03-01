package com.example.popping.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Image extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImageStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;

    private Image(String imageUrl, ImageStatus status, Post post) {
        this.imageUrl = imageUrl;
        this.status = status;
        this.post = post;
    }

    public static Image createTemp(String imageUrl) {
        validateImageUrl(imageUrl);
        return new Image(imageUrl, ImageStatus.TEMP, null);
    }

    public void attachTo(Post post) {
        if (post == null) throw new IllegalArgumentException("post는 필수입니다.");
        this.post = post;
        this.status = ImageStatus.PERMANENT;
    }

    public void detach() {
        this.post = null;
        this.status = ImageStatus.TEMP;
    }

    public boolean isTemp() {
        return status == ImageStatus.TEMP;
    }

    public boolean isPermanent() {
        return status == ImageStatus.PERMANENT;
    }

    private static void validateImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl은 필수입니다.");
        }
    }
}