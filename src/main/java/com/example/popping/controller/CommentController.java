package com.example.popping.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.CommentPageResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.service.CommentService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/boards/{slug}/{postId}/comments")
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    public CommentPageResponse getComments(
            @PathVariable Long postId,
            @RequestParam int page
    ) {
        return commentService.getCommentPage(postId, page);
    }

    @PostMapping("/member")
    public ResponseEntity<?> createMemberComment(@PathVariable String slug,
                                                 @PathVariable Long postId,
                                                 @Valid @RequestBody MemberCommentCreateRequest dto,
                                                 @RequestParam(required = false) Long parentId,
                                                 @AuthenticationPrincipal UserPrincipal user) {
        try {
            Long newCommentId = commentService.createMemberComment(postId, dto, user, parentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/guest")
    public ResponseEntity<?> createGuestComment(@PathVariable String slug,
                                                @PathVariable Long postId,
                                                @Valid @RequestBody GuestCommentCreateRequest dto,
                                                @RequestParam(required = false) Long parentId) {
        try {
            Long newCommentId = commentService.createGuestComment(postId, dto, parentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable String slug,
                                           @PathVariable Long postId,
                                           @PathVariable Long commentId,
                                           @AuthenticationPrincipal UserPrincipal user) {
        try {
            commentService.deleteComment(commentId, user);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{commentId}/guest")
    public ResponseEntity<?> deleteCommentAsGuest(@PathVariable String slug,
                                                  @PathVariable Long postId,
                                                  @PathVariable Long commentId,
                                                  @RequestBody String password) {
        try {
            commentService.deleteCommentAsGuest(commentId, password);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}