package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;

@Getter
@Setter
@NoArgsConstructor
public class MemberCommentCreateRequest {

    @NotBlank(message = "내용은 필수입니다.")
    @Size(min = 1, max = 500, message = "내용은 1자 이상 500자 이하여야 합니다.")
    private String content;

    public Comment toEntity(User author, Post post, Comment parent) {
        return Comment.builder()
                .content(this.content)
                .author(author)
                .post(post)
                .parent(parent)
                .depth(parent != null ? parent.getDepth() + 1 : 0)
                .build();
    }
}
