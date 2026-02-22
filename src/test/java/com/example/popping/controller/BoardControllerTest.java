package com.example.popping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.popping.common.HtmlSanitizer;
import com.example.popping.config.web.GlobalBindingConfig;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.domain.UserRole;
import com.example.popping.service.BoardService;
import com.example.popping.service.PostService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BoardController.class)
@Import({GlobalBindingConfig.class, HtmlSanitizer.class})
class BoardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BoardService boardService;

    @MockitoBean
    private PostService postService;

    @Test
    @WithMockUser
    @DisplayName("게시판 생성 시 폼 데이터(ModelAttribute)의 XSS 스크립트가 제거되어야 한다")
    void createBoardXssTest() throws Exception {
        // given
        String dirtyName = "<script>alert('xss')</script>공지사항";
        String cleanName = "공지사항";
        String dirtyDescription = "<b>내용</b><iframe src='...'></iframe>";
        String cleanDescription = "내용"; // Safelist.none() 사용 시 모든 태그 제거
        String normalSlug = "notice-board"; // 필수 값인 slug 추가

        UserPrincipal mockPrincipal = UserPrincipal.builder()
                .nickname("테스트유저")
                .passwordHash("password")
                .role(UserRole.USER)
                .build();

        // when
        mockMvc.perform(post("/boards")
                        .with(csrf())
                        .with(user(mockPrincipal))
                        // @ModelAttribute는 contentType이 application/x-www-form-urlencoded 입니다.
                        .param("name", dirtyName)
                        .param("description", dirtyDescription)
                        .param("slug", normalSlug)) // 👈 slug 파라미터 추가
                .andExpect(status().is3xxRedirection()); // 리다이렉트 응답 확인

        // then
        // 서비스의 createBoard 메서드에 전달된 DTO 내부 값이 필터링되었는지 검증
        verify(boardService).createBoard(
                argThat(dto -> dto.name().equals(cleanName) &&
                        dto.description().equals(cleanDescription)),
                any() // loginUser
        );
    }
}
