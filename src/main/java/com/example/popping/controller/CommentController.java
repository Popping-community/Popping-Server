package com.example.popping.controller;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.service.CommentService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/boards/{slug}/{postId}/comments")
public class CommentController {

    private final CommentService commentService;

    @PostMapping("/member")
    public String createMemberComment(@PathVariable String slug,
                                      @PathVariable Long postId,
                                      @Valid @ModelAttribute MemberCommentCreateRequest dto,
                                      BindingResult bindingResult,
                                      @RequestParam(required = false) Long parentId,
                                      @AuthenticationPrincipal UserPrincipal user) {
        if (bindingResult.hasErrors()) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-form";
        }

        Long newCommentId = commentService.createMemberComment(postId, dto, user, parentId);

        if (parentId != null) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + parentId;
        } else {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + newCommentId;
        }
    }

    @PostMapping("/guest")
    public String createGuestComment(@PathVariable String slug,
                                     @PathVariable Long postId,
                                     @Valid @ModelAttribute GuestCommentCreateRequest dto,
                                     BindingResult bindingResult,
                                     @RequestParam(required = false) Long parentId) {
        if (bindingResult.hasErrors()) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-form";
        }

        Long newCommentId = commentService.createGuestComment(postId, dto, parentId);

        if (parentId != null) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + parentId;
        } else {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + newCommentId;
        }
    }

    @PostMapping("/{commentId}/delete")
    public String deleteComment(@PathVariable String slug,
                                @PathVariable Long postId,
                                @PathVariable Long commentId,
                                @AuthenticationPrincipal UserPrincipal user) {
        Long parentCommentId = commentService.getParentCommentId(commentId);
        Long previousCommentId = null;

        if (parentCommentId == null) {
            previousCommentId = commentService.getPreviousCommentId(postId, commentId);
        }

        commentService.deleteComment(commentId, user);

        if (parentCommentId != null) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + parentCommentId;
        } else if (previousCommentId != null) {
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + previousCommentId;
        } else {
            return "redirect:/boards/" + slug + "/" + postId + "#comments-section";
        }
    }

    @PostMapping("/{commentId}/delete-guest")
    public String deleteCommentAsGuest(@PathVariable String slug,
                                       @PathVariable Long postId,
                                       @PathVariable Long commentId,
                                       @RequestParam String password,
                                       RedirectAttributes redirectAttributes) {
        try {
            Long parentCommentId = commentService.getParentCommentId(commentId);
            Long previousCommentId = null;

            if (parentCommentId == null) {
                previousCommentId = commentService.getPreviousCommentId(postId, commentId);
            }

            commentService.deleteCommentAsGuest(commentId, password);

            if (parentCommentId != null) {
                return "redirect:/boards/" + slug + "/" + postId + "#comment-" + parentCommentId;
            } else if (previousCommentId != null) {
                return "redirect:/boards/" + slug + "/" + postId + "#comment-" + previousCommentId;
            } else {
                return "redirect:/boards/" + slug + "/" + postId + "#comments-section";
            }
        } catch (AccessDeniedException e) {
            redirectAttributes.addAttribute("errorCommentId", commentId);
            return "redirect:/boards/" + slug + "/" + postId + "#comment-" + commentId;
        }
    }
}