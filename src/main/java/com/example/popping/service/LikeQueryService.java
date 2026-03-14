package com.example.popping.service;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.repository.LikeRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LikeQueryService {

    private final LikeRepository likeRepository;
    private final UserService userService;

    public Map<Long, Set<Like.Type>> getReactionMap(Like.TargetType targetType,
                                                    Collection<Long> targetIds,
                                                    UserPrincipal principal,
                                                    String guestIdentifier) {
        if (targetIds == null || targetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        if (principal != null) {
            User user = userService.getLoginUserById(principal.getUserId());
            return toReactionMap(likeRepository.findAllByTargetTypeAndTargetIdInAndUser(targetType, targetIds, user));
        }

        if (guestIdentifier == null || guestIdentifier.isBlank()) {
            return Collections.emptyMap();
        }

        return toReactionMap(likeRepository.findAllByTargetTypeAndTargetIdInAndGuestIdentifier(targetType, targetIds, guestIdentifier));
    }

    private Map<Long, Set<Like.Type>> toReactionMap(Collection<Like> likes) {
        return likes.stream().collect(Collectors.groupingBy(
                Like::getTargetId,
                Collectors.mapping(Like::getType, Collectors.toSet())
        ));
    }
}
