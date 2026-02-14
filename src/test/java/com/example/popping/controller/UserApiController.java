package com.example.popping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.example.popping.common.HtmlSanitizer;
import com.example.popping.service.UserService;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserApiController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({HtmlSanitizer.class})
class UserApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    @DisplayName("아이디 중복 체크 - 중복된 경우 true를 반환해야 한다")
    void checkLoginId_Duplicate_ReturnsTrue() throws Exception {
        // given
        String loginId = "testUser";
        given(userService.checkLoginIdDuplicate(loginId)).willReturn(true);

        // when & then
        mockMvc.perform(get("/api/users/check-login-id")
                        .param("loginId", loginId)) // ?loginId=testUser 전송
                .andExpect(status().isOk()) // 200 OK 확인
                .andExpect(content().string("true")); // 응답 바디가 "true"인지 확인
    }

    @Test
    @DisplayName("닉네임 중복 체크 - 사용 가능한 경우 false를 반환해야 한다")
    void checkNickname_Available_ReturnsFalse() throws Exception {
        // given
        String nickname = "uniqueNick";
        given(userService.checkNicknameDuplicate(nickname)).willReturn(false);

        // when & then
        mockMvc.perform(get("/api/users/check-nickname")
                        .param("nickname", nickname))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }
}