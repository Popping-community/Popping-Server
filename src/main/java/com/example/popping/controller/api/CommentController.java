package com.example.popping.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.*;
import com.example.popping.service.CommentService;

@RestController
@RequiredArgsConstructor
@RequestMapping(CommentController.BASE_PATH)
public class CommentController {

    static final String BASE_PATH = "/boards/{slug}/{postId}/comments";

    private final CommentService commentService;

    @GetMapping
    public CommentPageResponse getComments(
            @PathVariable String slug,
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") int page
    ) {
        return commentService.getCommentPage(postId, page);
    }

    @PostMapping("/member")
    public ResponseEntity<CreatedCommentIdResponse> createMemberComment(
            @PathVariable String slug,
            @PathVariable Long postId,
            @Valid @RequestBody MemberCommentCreateRequest dto,
            @RequestParam(required = false) Long parentId,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        Long newCommentId = commentService.createMemberComment(postId, dto, user, parentId);
        return ResponseEntity.ok(new CreatedCommentIdResponse(newCommentId));
    }

    @PostMapping("/guest")
    public ResponseEntity<CreatedCommentIdResponse> createGuestComment(
            @PathVariable String slug,
            @PathVariable Long postId,
            @Valid @RequestBody GuestCommentCreateRequest dto,
            @RequestParam(required = false) Long parentId
    ) {
        Long newCommentId = commentService.createGuestComment(postId, dto, parentId);
        return ResponseEntity.ok(new CreatedCommentIdResponse(newCommentId));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable String slug,
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        commentService.deleteComment(commentId, user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{commentId}/guest")
    public ResponseEntity<Void> deleteCommentAsGuest(
            @PathVariable String slug,
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @Valid @RequestBody GuestPasswordRequest req
    ) {
        commentService.deleteCommentAsGuest(commentId, req.password());
        return ResponseEntity.noContent().build();
    }
}