package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;

@Getter
@Setter
@NoArgsConstructor
public class GuestCommentCreateRequest {

    @NotBlank(message = "내용은 필수입니다.")
    private String content;

    @NotBlank(message = "닉네임은 필수입니다.")
    private String guestNickname;

    @NotBlank(message = "비밀번호는 필수입니다.")
    private String guestPassword;

    public Comment toEntity(Post post, Comment parent, String passwordHash) {
        return Comment.builder()
                .content(this.content)
                .guestNickname(this.guestNickname)
                .guestPasswordHash(passwordHash)
                .post(post)
                .parent(parent)
                .build();
    }
}
