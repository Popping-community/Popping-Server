package com.example.popping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.popping.domain.Image;
import com.example.popping.domain.ImageStatus;
import com.example.popping.domain.Post;

public interface ImageRepository extends JpaRepository<Image, Long> {
    List<Image> findAllByPost(Post post);

    List<Image> findAllByImageUrlIn(List<String> imageUrls);

    List<Image> findAllByStatus(ImageStatus imageStatus);
}
