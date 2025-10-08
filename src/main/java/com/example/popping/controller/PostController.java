package com.example.popping.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.BindingResult;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.service.CommentService;
import com.example.popping.service.PostService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/boards/{slug}")
public class PostController {

    private final PostService postService;
    private final CommentService commentService;

    @GetMapping("/{postId}")
    public String getPost(@PathVariable String slug, @PathVariable Long postId,
                          Model model) {
        PostResponse postResponse = postService.getPostResponse(postId);
        CommentPageResponse commentPageResponses = commentService.getCommentPage(postId, 0);
        model.addAttribute("post", postResponse);
        model.addAttribute("comments", commentPageResponses);
        model.addAttribute("slug", slug);
        return "post/detail";
    }

    @GetMapping("/new")
    public String newPostForm(@ModelAttribute MemberPostCreateRequest memberPostCreateRequest,
                              @PathVariable String slug,
                              Model model) {
        model.addAttribute("slug", slug);
        return "post/form";
    }

    @GetMapping("/new-guest")
    public String newGuestPostForm(@ModelAttribute GuestPostCreateRequest guestPostCreateRequest,
                                   @PathVariable String slug,
                                   Model model) {
        model.addAttribute("slug", slug);
        return "post/form";
    }

    @PostMapping("/member")
    public String createPostAsMember(@PathVariable String slug,
                                     @Valid @ModelAttribute MemberPostCreateRequest dto,
                                     BindingResult bindingResult,
                                     @AuthenticationPrincipal UserPrincipal loginUser,
                                     Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("slug", slug);
            return "post/form";
        }
        Long postId = postService.createMemberPost(slug, dto, loginUser);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @PostMapping("/guest")
    public String createPostAsGuest(@PathVariable String slug,
                                    @Valid @ModelAttribute GuestPostCreateRequest dto,
                                    BindingResult bindingResult,
                                    Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("slug", slug);
            return "post/form";
        }
        Long postId = postService.createGuestPost(slug, dto);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @GetMapping("/{postId}/edit-password")
    public String editPasswordForm(@PathVariable String slug,
                                   @PathVariable Long postId,
                                   @RequestParam(required = false) String error,
                                   Model model) {
        model.addAttribute("slug", slug);
        model.addAttribute("postId", postId);
        model.addAttribute("error", error);
        return "post/edit-password-form";
    }

    @PostMapping("/{postId}/edit-password")
    public String verifyEditPassword(@PathVariable String slug,
                                     @PathVariable Long postId,
                                     @RequestParam String password,
                                     HttpSession session) {
        boolean isValid = postService.verifyGuestPassword(postId, password);
        if (!isValid) {
            return "redirect:/boards/" + slug + "/" + postId + "/edit-password?error=true";
        }

        session.setAttribute("verifiedPost:" + postId, true);
        return "redirect:/boards/" + slug + "/" + postId + "/edit-guest";
    }

    @GetMapping("/{postId}/edit")
    public String editPostForm(@ModelAttribute MemberPostUpdateRequest memberPostUpdateRequest,
                               @PathVariable String slug,
                               @PathVariable Long postId,
                               @AuthenticationPrincipal UserPrincipal loginUser,
                               Model model) {
        PostResponse dto = postService.getMemberPostForEdit(postId, loginUser);
        model.addAttribute("form", dto);
        model.addAttribute("slug", slug);
        return "post/edit-form";
    }

    @GetMapping("/{postId}/edit-guest")
    public String editGuestPostForm(@ModelAttribute GuestPostUpdateRequest guestPostUpdateRequest,
                               @PathVariable String slug,
                               @PathVariable Long postId,
                               HttpSession session,
                               Model model) {
        Boolean verified = (Boolean) session.getAttribute("verifiedPost:" + postId);
        if (verified == null || !verified) {
            return "redirect:/boards/" + slug + "/" + postId + "/edit-password";
        }
        PostResponse dto = postService.getPostResponse(postId);
        model.addAttribute("form", dto);
        model.addAttribute("slug", slug);
        return "post/edit-form";
    }

    @PostMapping("/{postId}/edit")
    public String updatePostAsMember(@AuthenticationPrincipal UserPrincipal loginUser,
                                     @PathVariable String slug,
                                     @PathVariable Long postId,
                                     @Valid @ModelAttribute MemberPostUpdateRequest dto,
                                     BindingResult bindingResult,
                                     Model model) {
        if(bindingResult.hasErrors()){
            PostResponse postResponse = postService.getMemberPostForEdit(postId, loginUser);
            model.addAttribute("form", postResponse);
            model.addAttribute("slug", slug);
            return "post/edit-form";
        }
        postService.updatePost(postId, dto, loginUser);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @PostMapping("/{postId}/edit-guest")
    public String updatePostAsGuest(@PathVariable String slug,
                                    @PathVariable Long postId,
                                    @Valid @ModelAttribute GuestPostUpdateRequest dto,
                                    BindingResult bindingResult,
                                    Model model,
                                    HttpSession session) {
        if(bindingResult.hasErrors()){
            PostResponse postResponse = postService.getPostResponse(postId);
            model.addAttribute("form", postResponse);
            model.addAttribute("slug", slug);
            return "post/edit-form";
        }
        Boolean verified = (Boolean) session.getAttribute("verifiedPost:" + postId);
        if (verified == null || !verified) {
            return "redirect:/boards/" + slug + "/" + postId + "/edit-password";
        }

        postService.updatePostAsGuest(postId, dto);
        session.removeAttribute("verifiedPost:" + postId);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @GetMapping("/{postId}/delete-password")
    public String deletePasswordForm(@PathVariable String slug,
                                     @PathVariable Long postId,
                                     @RequestParam(required = false) String error,
                                     Model model) {
        model.addAttribute("slug", slug);
        model.addAttribute("postId", postId);
        model.addAttribute("error", error != null);
        return "post/delete-password-form";
    }

    @PostMapping("/{postId}/delete-password")
    public String verifyAndDelete(@PathVariable String slug,
                                  @PathVariable Long postId,
                                  @RequestParam String password) {
        if (postService.verifyGuestPassword(postId, password)) {
            postService.deletePostAsGuest(postId);
            return "redirect:/boards/" + slug;
        }
        return "redirect:/boards/" + slug + "/" + postId + "/delete-password?error=true";
    }

    @PostMapping("/{postId}/delete")
    public String deletePostByMember(@AuthenticationPrincipal UserPrincipal loginUser,
                                     @PathVariable String slug,
                                     @PathVariable Long postId) {
        postService.deletePost(postId, loginUser);
        return "redirect:/boards/" + slug;
    }
}
