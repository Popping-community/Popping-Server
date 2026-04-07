package com.example.popping.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.PostRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeCountReconcileScheduler {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    @Transactional
    @Scheduled(cron = "0 0 4 * * *")
    public void reconcileLikeCounts() {
        int fixedPosts = postRepository.reconcileLikeCounts();
        int fixedComments = commentRepository.reconcileLikeCounts();
        if (fixedPosts > 0 || fixedComments > 0) {
            log.warn("likeCount reconcile: fixed {} posts, {} comments", fixedPosts, fixedComments);
        } else {
            log.info("likeCount reconcile: no discrepancies found");
        }
    }
}
