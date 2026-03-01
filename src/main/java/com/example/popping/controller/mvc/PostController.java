package com.example.popping.controller.mvc;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.service.CommentService;
import com.example.popping.service.PostService;

@Controller
@RequiredArgsConstructor
@RequestMapping(PostController.BASE_PATH)
public class PostController {

    static final String BASE_PATH = "/boards/{slug}";

    private static final String REDIRECT_PREFIX = "redirect:/boards/";
    private static final String SESSION_VERIFIED_PREFIX = "verified_post_";

    private static final String ATTR_SLUG = "slug";
    private static final String ATTR_POST = "post";
    private static final String ATTR_COMMENTS = "comments";
    private static final String ATTR_FORM = "form";

    private static final String VIEW_POST_DETAIL = "post/detail";
    private static final String VIEW_POST_FORM = "post/form";
    private static final String VIEW_POST_EDIT_FORM = "post/edit-form";
    private static final String VIEW_EDIT_PASSWORD_FORM = "post/edit-password-form";
    private static final String VIEW_DELETE_PASSWORD_FORM = "post/delete-password-form";

    private final PostService postService;
    private final CommentService commentService;

    @ModelAttribute(ATTR_SLUG)
    public String slug(@PathVariable String slug) {
        return slug;
    }

    @GetMapping("/{postId}")
    public String getPost(@PathVariable Long postId, Model model) {
        PostResponse postResponse = postService.getPostResponse(postId);
        CommentPageResponse commentPageResponses = commentService.getCommentPage(postId, 0);

        model.addAttribute(ATTR_POST, postResponse);
        model.addAttribute(ATTR_COMMENTS, commentPageResponses);
        return VIEW_POST_DETAIL;
    }

    @GetMapping("/new")
    public String newPostForm(@ModelAttribute("form") MemberPostCreateRequest form) {
        return VIEW_POST_FORM;
    }

    @GetMapping("/new-guest")
    public String newGuestPostForm(@ModelAttribute("form") GuestPostCreateRequest form) {
        return VIEW_POST_FORM;
    }

    @PostMapping("/member")
    public String createPostAsMember(@PathVariable String slug,
                                     @Valid @ModelAttribute("form") MemberPostCreateRequest dto,
                                     BindingResult bindingResult,
                                     @AuthenticationPrincipal UserPrincipal loginUser) {
        if (bindingResult.hasErrors()) {
            return VIEW_POST_FORM;
        }

        Long postId = postService.createMemberPost(slug, dto, loginUser);
        return redirectToPostDetail(slug, postId);
    }

    @PostMapping("/guest")
    public String createPostAsGuest(@PathVariable String slug,
                                    @Valid @ModelAttribute("form") GuestPostCreateRequest dto,
                                    BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return VIEW_POST_FORM;
        }

        Long postId = postService.createGuestPost(slug, dto);
        return redirectToPostDetail(slug, postId);
    }

    @GetMapping("/{postId}/edit-password")
    public String editPasswordForm(@PathVariable Long postId,
                                   @RequestParam(required = false) String error,
                                   Model model) {
        model.addAttribute("postId", postId);
        model.addAttribute("error", error);
        return VIEW_EDIT_PASSWORD_FORM;
    }

    @PostMapping("/{postId}/edit-password")
    public String verifyEditPassword(@PathVariable String slug,
                                     @PathVariable Long postId,
                                     @RequestParam String password,
                                     HttpSession session) {
        boolean isValid = postService.verifyGuestPassword(postId, password);
        if (!isValid) {
            return redirectToEditPassword(slug, postId, true);
        }

        markVerified(session, postId);
        return redirectToEditGuest(slug, postId);
    }

    @GetMapping("/{postId}/edit")
    public String editPostForm(@PathVariable String slug,
                               @PathVariable Long postId,
                               @AuthenticationPrincipal UserPrincipal loginUser,
                               Model model) {
        PostResponse dto = postService.getMemberPostForEdit(postId, loginUser);
        model.addAttribute(ATTR_POST, dto);
        model.addAttribute(ATTR_FORM, PostEditForm.from(dto));
        return VIEW_POST_EDIT_FORM;
    }

    @GetMapping("/{postId}/edit-guest")
    public String editGuestPostForm(@PathVariable String slug,
                                    @PathVariable Long postId,
                                    HttpSession session,
                                    Model model) {
        if (!isVerified(session, postId)) {
            return redirectToEditPassword(slug, postId, false);
        }

        PostResponse dto = postService.getPostResponse(postId);
        model.addAttribute(ATTR_POST, dto);
        model.addAttribute(ATTR_FORM, PostEditForm.from(dto));
        return VIEW_POST_EDIT_FORM;
    }

    @PostMapping("/{postId}/edit")
    public String updatePostAsMember(@AuthenticationPrincipal UserPrincipal loginUser,
                                     @PathVariable String slug,
                                     @PathVariable Long postId,
                                     @Valid @ModelAttribute("form") MemberPostUpdateRequest dto,
                                     BindingResult bindingResult,
                                     Model model) {
        if (bindingResult.hasErrors()) {
            PostResponse postResponse = postService.getMemberPostForEdit(postId, loginUser);
            model.addAttribute(ATTR_POST, postResponse);
            return VIEW_POST_EDIT_FORM;
        }

        postService.updatePost(postId, dto, loginUser);
        return redirectToPostDetail(slug, postId);
    }

    @PostMapping("/{postId}/edit-guest")
    public String updatePostAsGuest(@PathVariable String slug,
                                    @PathVariable Long postId,
                                    @Valid @ModelAttribute("form") GuestPostUpdateRequest dto,
                                    BindingResult bindingResult,
                                    Model model,
                                    HttpSession session) {
        if (!isVerified(session, postId)) {
            return redirectToEditPassword(slug, postId, false);
        }

        if (bindingResult.hasErrors()) {
            PostResponse postResponse = postService.getPostResponse(postId);
            model.addAttribute(ATTR_POST, postResponse);
            return VIEW_POST_EDIT_FORM;
        }

        postService.updatePostAsGuest(postId, dto);
        clearVerified(session, postId);
        return redirectToPostDetail(slug, postId);
    }

    @GetMapping("/{postId}/delete-password")
    public String deletePasswordForm(@PathVariable Long postId,
                                     @RequestParam(required = false) String error,
                                     Model model) {
        model.addAttribute("postId", postId);
        model.addAttribute("error", error != null);
        return VIEW_DELETE_PASSWORD_FORM;
    }

    @PostMapping("/{postId}/delete-password")
    public String verifyAndDelete(@PathVariable String slug,
                                  @PathVariable Long postId,
                                  @RequestParam String password) {
        if (postService.verifyGuestPassword(postId, password)) {
            postService.deletePostAsGuest(postId);
            return redirectToBoard(slug);
        }
        return redirectToDeletePassword(slug, postId, true);
    }

    @PostMapping("/{postId}/delete")
    public String deletePostByMember(@AuthenticationPrincipal UserPrincipal loginUser,
                                     @PathVariable String slug,
                                     @PathVariable Long postId) {
        postService.deletePost(postId, loginUser);
        return redirectToBoard(slug);
    }

    private String verifiedKey(Long postId) {
        return SESSION_VERIFIED_PREFIX + postId;
    }

    private boolean isVerified(HttpSession session, Long postId) {
        return Boolean.TRUE.equals(
                session.getAttribute(verifiedKey(postId))
        );
    }

    private void markVerified(HttpSession session, Long postId) {
        session.setAttribute(verifiedKey(postId), true);
    }

    private void clearVerified(HttpSession session, Long postId) {
        session.removeAttribute(verifiedKey(postId));
    }

    private String redirectToPostDetail(String slug, Long postId) {
        return "redirect:/boards/" + slug + "/" + postId;
    }

    private String redirectToBoard(String slug) {
        return "redirect:/boards/" + slug;
    }

    private String redirectToEditGuest(String slug, Long postId) {
        return REDIRECT_PREFIX + slug + "/" + postId + "/edit-guest";
    }

    private String redirectToEditPassword(String slug, Long postId, boolean error) {
        String base = REDIRECT_PREFIX + slug + "/" + postId + "/edit-password";
        return error ? base + "?error=true" : base;
    }

    private String redirectToDeletePassword(String slug, Long postId, boolean error) {
        String base = REDIRECT_PREFIX + slug + "/" + postId + "/delete-password";
        return error ? base + "?error=true" : base;
    }
}
