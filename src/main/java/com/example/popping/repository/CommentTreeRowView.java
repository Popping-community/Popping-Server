package com.example.popping.repository;

import java.time.LocalDateTime;

public interface CommentTreeRowView {
    Long getId();
    Long getParentId();
    Long getPostId();
    String getContent();
    Integer getDepth();
    LocalDateTime getCreatedAt();
    Long getUserId();
    String getGuestNickname();
    Integer getLikeCount();
    Integer getDislikeCount();
}
