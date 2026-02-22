package com.example.popping.dto;

public record PostEditForm(

        String title,
        String content,
        String guestNickname,
        String guestPassword

) {
    public static PostEditForm from(PostResponse dto) {
        return new PostEditForm(
                dto.title(),
                dto.content(),
                dto.guestNickname(),
                ""
        );
    }
}
