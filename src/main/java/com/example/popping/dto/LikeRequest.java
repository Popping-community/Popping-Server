package com.example.popping.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;

@Getter
@Setter
@NoArgsConstructor
public class LikeRequest {
    @NotNull
    private Long targetId;
    @NotNull
    private Like.TargetType targetType;
    @NotNull
    private Like.Type type;
    private String guestIdentifier;

    public Like toEntity(User user) {
        return Like.builder()
                .targetId(targetId)
                .targetType(targetType)
                .type(type)
                .user(user)
                .guestIdentifier(guestIdentifier)
                .build();
    }
}
