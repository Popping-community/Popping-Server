package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;

@Getter
@Setter
@NoArgsConstructor
public class GuestPostCreateRequest {

    @NotBlank(message = "제목은 필수입니다.")
    private String title;

    @NotBlank(message = "내용은 필수입니다.")
    private String content;

    @NotBlank(message = "닉네임은 필수입니다.")
    private String guestNickname;

    @NotBlank(message = "비밀번호는 필수입니다.")
    private String guestPassword;

    public Post toEntity(Board board, String passwordHash) {
        return Post.builder()
                .title(this.title)
                .content(this.content)
                .guestNickname(guestNickname)
                .guestPasswordHash(passwordHash)
                .board(board)
                .build();
    }
}

