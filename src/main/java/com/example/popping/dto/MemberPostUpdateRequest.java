package com.example.popping.dto;

import jakarta.persistence.Lob;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MemberPostUpdateRequest {

    @NotBlank(message = "제목은 필수입니다.")
    private String title;

    @Lob
    @NotBlank(message = "내용은 필수입니다.")
    private String content;
}
