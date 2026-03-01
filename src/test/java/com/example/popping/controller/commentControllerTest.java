package com.example.popping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.popping.common.HtmlSanitizer;
import com.example.popping.config.web.GlobalBindingConfig;
import com.example.popping.controller.api.CommentController;
import com.example.popping.service.CommentService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommentController.class)
@Import({GlobalBindingConfig.class, HtmlSanitizer.class})
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @Test
    @WithMockUser
    @DisplayName("댓글 내용(content)에 포함된 스크립트가 필터링되어 서비스에 전달되어야 한다")
    void sanitizeRequestBodyTest() throws Exception {
        // given
        String normalSlug = "my-slug";
        Long postId = 1L;

        // 공격 코드와 예상되는 필터링 결과
        String dirtyContent = "<script>alert('xss')</script>Hello";
        String cleanContent = "Hello"; // HtmlSanitizer(Safelist.none()) 기준 태그 전면 제거

        // JSON 생성 (content 필드에 dirtyContent 주입)
        String jsonRequest = String.format("{\"content\":\"%s\"}", dirtyContent);

        // when
        mockMvc.perform(post("/boards/{slug}/{postId}/comments/member", normalSlug, postId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk());

        // then
        // 1. 서비스의 메서드가 호출되었는지 확인
        // 2. 특히 3번째 인자인 Request DTO의 content가 cleanContent로 바뀌었는지 확인
        verify(commentService).createMemberComment(
                eq(postId),
                argThat(request -> request.content().equals(cleanContent)), // DTO 내부 필드 검증
                any(),
                any()
        );
    }
}
