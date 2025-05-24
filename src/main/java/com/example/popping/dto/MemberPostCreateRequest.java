package com.example.popping.dto;

import jakarta.persistence.Lob;
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
public class MemberPostCreateRequest {

    @NotBlank(message = "제목은 필수입니다.")
    private String title;

    @Lob
    @NotBlank(message = "내용은 필수입니다.")
    private String content;

    public Post toEntity(User author, Board board) {
        return Post.builder()
                .title(this.title)
                .content(this.content)
                .author(author)
                .board(board)
                .build();
    }
}