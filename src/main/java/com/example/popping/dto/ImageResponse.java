package com.example.popping.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ImageResponse {
    private String imageUrl;

    public static ImageResponse from(String imageUrl) {
        return ImageResponse.builder()
                .imageUrl(imageUrl)
                .build();
    }
}
