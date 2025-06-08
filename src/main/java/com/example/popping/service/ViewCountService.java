package com.example.popping.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.repository.PostRepository;

@Service
@RequiredArgsConstructor
public class ViewCountService {
    private final PostRepository postRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increaseView(Long postId) {
        postRepository.increaseViewCount(postId);
    }
}
