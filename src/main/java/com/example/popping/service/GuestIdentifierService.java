package com.example.popping.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GuestIdentifierService {

    @Value("${guest.identifier.hmac-secret}")
    private String secret;

    /**
     * UUID + HMAC 서명을 조합한 쿠키 값 생성.
     * 형태: "uuid.base64url(hmac-sha256(uuid, secret))"
     */
    public String generate() {
        String uuid = UUID.randomUUID().toString();
        return uuid + "." + sign(uuid);
    }

    /**
     * 쿠키 값에서 서명 검증 후 UUID 반환.
     * 서명이 유효하지 않으면 empty 반환.
     */
    public Optional<String> extractUuid(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        int dotIdx = cookieValue.lastIndexOf('.');
        if (dotIdx < 0) {
            return Optional.empty();
        }
        String uuid = cookieValue.substring(0, dotIdx);
        String sig = cookieValue.substring(dotIdx + 1);
        boolean valid = MessageDigest.isEqual(
                sign(uuid).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
        return valid ? Optional.of(uuid) : Optional.empty();
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
