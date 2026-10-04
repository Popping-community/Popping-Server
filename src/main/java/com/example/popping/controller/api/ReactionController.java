package com.example.popping.controller.api;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.popping.dto.ReactionCountsResponse;
import com.example.popping.service.ReactionReadService;

import lombok.RequiredArgsConstructor;

/** Current reaction counts for a post page, refetched by the browser to correct live updates. */
@RestController
@RequiredArgsConstructor
@RequestMapping(ReactionController.BASE_PATH)
public class ReactionController {

    static final String BASE_PATH = "/boards/{slug}/{postId}/reactions";

    private final ReactionReadService reactionReadService;

    @GetMapping
    public ResponseEntity<ReactionCountsResponse> getReactions(
            @PathVariable String slug,
            @PathVariable Long postId,
            @RequestParam(required = false) List<Long> commentIds
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reactionReadService.getReactionCounts(postId, commentIds));
    }
}
