package com.example.popping.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.example.popping.domain.Like;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The like broadcast is the contract between the server, the browser and, once relayed, the
 * other instances. Uses the application's own ObjectMapper configuration.
 */
@JsonTest
class LikeResponseJsonTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("좋아요 메시지는 enum을 이름으로, 버전을 숫자로 싣고 그대로 되읽힌다")
    void likeResponse_roundTrip_keepsEnumNamesAndVersion() throws Exception {
        LikeResponse response = new LikeResponse(
                10L, Like.TargetType.COMMENT, LikeResponse.LikeAction.UNDISLIKED, 3, 1, 42L);

        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("targetType").asText()).isEqualTo("COMMENT");
        assertThat(node.get("action").asText()).isEqualTo("UNDISLIKED");
        assertThat(node.get("reactionVersion").isIntegralNumber()).isTrue();
        assertThat(node.get("reactionVersion").asLong()).isEqualTo(42L);
        assertThat(node.get("likeCount").isIntegralNumber()).isTrue();
        assertThat(node.get("likeCount").asInt()).isEqualTo(3);
        assertThat(node.get("dislikeCount").asInt()).isEqualTo(1);
        assertThat(objectMapper.readValue(json, LikeResponse.class)).isEqualTo(response);
    }

    @Test
    @DisplayName("모르는 필드가 섞인 메시지도 읽는다 (서버 버전이 섞여 도는 배포 중)")
    void likeResponse_ignoresUnknownFields() throws Exception {
        String json = """
                {"targetId":10,"targetType":"POST","action":"LIKED","likeCount":5,"dislikeCount":0,
                 "reactionVersion":9,"addedLater":"x"}""";

        LikeResponse response = objectMapper.readValue(json, LikeResponse.class);

        assertThat(response.reactionVersion()).isEqualTo(9L);
        assertThat(response.targetType()).isEqualTo(Like.TargetType.POST);
    }
}
