package com.example.popping.dto;

import jakarta.persistence.Lob;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BoardUpdateRequest {

    @NotBlank(message = "게시판 이름은 필수입니다.")
    private String name;

    @Lob
    @NotBlank(message = "설명은 필수입니다.")
    private String description;
}
