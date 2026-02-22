package com.example.popping.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import com.example.popping.common.HtmlSanitizer;
import com.example.popping.dto.PostResponse;
import com.example.popping.service.CommentService;
import com.example.popping.service.PostService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostController.class)
@AutoConfigureMockMvc(addFilters = false)
class PostControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    private HtmlSanitizer htmlSanitizer;

    @MockBean
    PostService postService;

    @MockBean
    CommentService commentService;

    @BeforeEach
    void setUp() {
        when(htmlSanitizer.sanitize(anyString()))
                .thenAnswer(inv -> inv.getArgument(0, String.class));
    }

    @Test
    @DisplayName("게스트 비밀번호 검증 성공 -> 세션에 verified 저장 -> edit-guest 접근이 허용된다")
    void guest_verifyPassword_success_then_can_access_editGuest() throws Exception {
        // given
        String slug = "board-1";
        Long postId = 10L;
        String password = "1234";

        when(postService.verifyGuestPassword(postId, password)).thenReturn(true);

        PostResponse postResponse = new PostResponse(
                10L, "t", "c", "author", "board",
                null, "guestNick", 0L, 0, 0, 0
        );
        when(postService.getPostResponse(postId)).thenReturn(postResponse);

        MockHttpSession session = new MockHttpSession();

        // when 1) 비밀번호 검증 성공
        mockMvc.perform(post("/boards/{slug}/{postId}/edit-password", slug, postId)
                        .param("password", password)
                        .session(session))
                // then 1) edit-guest로 redirect
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/" + slug + "/" + postId + "/edit-guest"));

        // when 2) 같은 세션으로 edit-guest 접근
        mockMvc.perform(get("/boards/{slug}/{postId}/edit-guest", slug, postId)
                        .session(session))
                // then 2) redirect가 아니라 edit-form view 반환 (즉, 세션 인증 통과)
                .andExpect(status().isOk())
                .andExpect(view().name("post/edit-form"))
                .andExpect(model().attributeExists("post"))
                .andExpect(model().attributeExists("form"));

        verify(postService).verifyGuestPassword(postId, password);
        verify(postService).getPostResponse(postId);
    }

    @Test
    @DisplayName("게스트 비밀번호 검증 실패 -> 세션에 verified가 남지 않아 edit-guest 접근 시 다시 edit-password로 리다이렉트된다")
    void guest_verifyPassword_fail_then_cannot_access_editGuest() throws Exception {
        // given
        String slug = "board-1";
        Long postId = 10L;
        String password = "wrong";

        when(postService.verifyGuestPassword(postId, password)).thenReturn(false);

        MockHttpSession session = new MockHttpSession();

        // when 1) 비밀번호 검증 실패
        mockMvc.perform(post("/boards/{slug}/{postId}/edit-password", slug, postId)
                        .param("password", password)
                        .session(session))
                // then 1) edit-password로 redirect (error=true 같은 파라미터가 있으면 거기에 맞춰 검증)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/" + slug + "/" + postId + "/edit-password?error=true"));

        // when 2) 같은 세션으로 edit-guest 접근 시도
        mockMvc.perform(get("/boards/{slug}/{postId}/edit-guest", slug, postId)
                        .session(session))
                // then 2) 검증 안됐으니 edit-password로 다시 리다이렉트
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/" + slug + "/" + postId + "/edit-password"));

        verify(postService).verifyGuestPassword(postId, password);
        verify(postService, never()).getPostResponse(anyLong());
    }

    @Test
    @DisplayName("게스트 수정 요청: 세션 verified가 없으면 edit-password로 리다이렉트된다")
    void guest_update_without_verified_redirects_to_password() throws Exception {
        // given
        String slug = "board-1";
        Long postId = 10L;

        MockHttpSession session = new MockHttpSession(); // verified 표시 없는 세션

        // when / then
        mockMvc.perform(post("/boards/{slug}/{postId}/edit-guest", slug, postId)
                        .session(session)
                        .param("title", "t")
                        .param("content", "c")
                        .param("guestNickname", "n")
                        .param("guestPassword", "p"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/" + slug + "/" + postId + "/edit-password"));

        verify(postService, never()).updatePostAsGuest(anyLong(), any());
    }
}
