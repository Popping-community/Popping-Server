package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.popping.domain.Board;
import com.example.popping.domain.User;

@Getter
@Setter
@NoArgsConstructor
public class BoardCreateRequest {

    @NotBlank(message = "게시판 이름은 필수입니다.")
    private String name;

    @NotBlank(message = "설명은 필수입니다.")
    private String description;

    @NotBlank(message = "슬러그는 필수입니다.")
    private String slug;

    public Board toEntity(User user) {
        return Board.builder()
                .name(this.name)
                .description(this.description)
                .slug(this.slug)
                .createdBy(user)
                .build();
    }
}
