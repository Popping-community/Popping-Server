package com.example.popping.controller;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.config.security.SecurityConfig;
import com.example.popping.controller.api.CommentController;
import com.example.popping.domain.*;
import com.example.popping.exception.ApiExceptionHandler;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeRepository;
import com.example.popping.service.*;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real production SecurityFilterChain, controller/advice/service; mock persistence, not a login/DB test. */
class CommentSecurityBoundaryTest {
    private static final String MEMBER_DELETE = "/boards/test/7/comments/9";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private PostService posts;
    private CommentRepository comments;
    private UserService users;
    private Post post;
    private User author;
    private Comment memberComment;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Fixture {
        @Bean PostService posts() { return mock(PostService.class); }
        @Bean UserService users() { return mock(UserService.class); }
        @Bean CommentRepository comments() { return mock(CommentRepository.class); }
        @Bean LikeRepository likes() { return mock(LikeRepository.class); }
        @Bean GuestIdentifierService guests() { return mock(GuestIdentifierService.class); }
        @Bean CustomUserDetailsService loginUsers() { return mock(CustomUserDetailsService.class); }
        @Bean CacheManager caches() { return new CacheConfig().cacheManager(true, 5, true); }
        @Bean TransactionTemplate tx() {
            TransactionTemplate tx = mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(null);
            });
            return tx;
        }
        @Bean CommentService commentService(PostService posts, UserService users, CommentRepository comments,
                LikeRepository likes, PasswordEncoder guestPasswordEncoder, CacheManager caches,
                TransactionTemplate tx, ApplicationEventPublisher events, GuestIdentifierService guests) {
            return new CommentService(posts, users, comments, likes, guestPasswordEncoder, caches, tx, events, guests);
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Fixture.class, SecurityConfig.class, CommentController.class, ApiExceptionHandler.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        posts = context.getBean(PostService.class);
        users = context.getBean(UserService.class);
        comments = context.getBean(CommentRepository.class);
        post = mock(Post.class);
        when(post.getId()).thenReturn(7L);
        when(posts.getPostForUpdate(7L)).thenReturn(post);
        when(posts.getPost(7L)).thenReturn(post);
        author = mock(User.class);
        when(author.getId()).thenReturn(1L);
        when(author.getLoginId()).thenReturn("author");
        when(users.getLoginUserById(1L)).thenReturn(author);
        memberComment = Comment.createMemberComment("comment", author, post, null);
        when(comments.findById(9L)).thenReturn(Optional.of(memberComment));
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    private UserPrincipal principal(long id) {
        return UserPrincipal.builder().userId(id).loginId("user-" + id).role(UserRole.USER).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html"})
    void anonymousMemberDelete_isRejectedBeforeServiceReads(String accept) throws Exception {
        mvc.perform(delete(MEMBER_DELETE).accept(accept))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(posts, comments, users);
    }

    @Test
    void directServiceDelete_withoutPrincipalIsRejectedBeforeServiceReads() {
        CustomAppException error = assertThrows(CustomAppException.class,
                () -> context.getBean(CommentService.class).deleteComment(7L, 9L, null));
        assertEquals(ErrorType.NO_AUTHENTICATION, error.getErrorType());
        verifyNoInteractions(posts, comments, users);
    }

    @Test
    void authenticatedAuthor_canDelete() throws Exception {
        mvc.perform(delete(MEMBER_DELETE).with(user(principal(1L))))
                .andExpect(status().isNoContent());
        verify(posts).getPostForUpdate(7L);
        verify(post).decreaseCommentCount(1);
        verify(comments).delete(memberComment);
    }

    @Test
    void authenticatedNonAuthor_isForbiddenWithoutDeleting() throws Exception {
        User other = mock(User.class);
        when(other.getId()).thenReturn(2L);
        when(other.getLoginId()).thenReturn("other");
        when(users.getLoginUserById(2L)).thenReturn(other);
        mvc.perform(delete(MEMBER_DELETE).with(user(principal(2L))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verify(comments, never()).delete(any());
        verify(post, never()).decreaseCommentCount(anyInt());
    }

    @Test
    void anonymousGuestDelete_stillAcceptsCorrectPassword() throws Exception {
        PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
        Comment guest = Comment.createGuestComment("comment", "guest", encoder.encode("password"), post, null);
        when(comments.findById(9L)).thenReturn(Optional.of(guest));
        mvc.perform(delete(MEMBER_DELETE + "/guest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password\"}"))
                .andExpect(status().isNoContent());
        verify(comments).delete(guest);
    }

    @Test
    void anonymousGuestDelete_stillRejectsWrongPassword() throws Exception {
        PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
        Comment guest = Comment.createGuestComment("comment", "guest", encoder.encode("password"), post, null);
        when(comments.findById(9L)).thenReturn(Optional.of(guest));
        mvc.perform(delete(MEMBER_DELETE + "/guest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden());
        verify(comments, never()).delete(any());
    }

    @Test
    void publicCommentRead_remainsPublic() throws Exception {
        when(comments.findPagedCommentTree(7L, CommentService.COMMENTS_SIZE, 0)).thenReturn(List.of());
        mvc.perform(get("/boards/test/7/comments")).andExpect(status().isOk());
        verify(posts).getPost(7L);
    }

    @Test
    void htmlPage_keepsExistingLoginRedirect() throws Exception {
        mvc.perform(get("/boards/new").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login"));
        verifyNoInteractions(posts, comments, users);
    }

    @Test
    void otherProtectedApi_keepsExistingLoginRedirectForJsonAccept() throws Exception {
        // Preserve today's non-target contract; a future API-wide 401 migration may change it.
        mvc.perform(post("/boards/test/7/comments/member").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost/login"));
        verifyNoInteractions(posts, comments, users);
    }
}
