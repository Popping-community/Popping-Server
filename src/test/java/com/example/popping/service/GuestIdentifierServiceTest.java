package com.example.popping.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class GuestIdentifierServiceTest {

    private GuestIdentifierService service;

    @BeforeEach
    void setUp() {
        service = new GuestIdentifierService();
        ReflectionTestUtils.setField(service, "secret", "test-secret-key");
    }

    @Test
    @DisplayName("generate(): uuid.signature 형태의 값을 반환한다")
    void generate_returnsUuidDotSignatureFormat() {
        String value = service.generate();

        assertTrue(value.contains("."), "uuid.sig 형태여야 한다");
        String[] parts = value.split("\\.", 2);
        assertEquals(2, parts.length);
        assertFalse(parts[0].isBlank());
        assertFalse(parts[1].isBlank());
    }

    @Test
    @DisplayName("generate()로 만든 값은 extractUuid()로 UUID를 복원할 수 있다")
    void extractUuid_validSignature_returnsUuid() {
        String generated = service.generate();
        String expectedUuid = generated.substring(0, generated.lastIndexOf('.'));

        Optional<String> result = service.extractUuid(generated);

        assertTrue(result.isPresent());
        assertEquals(expectedUuid, result.get());
    }

    @Test
    @DisplayName("extractUuid(): 서명이 변조된 경우 empty를 반환한다")
    void extractUuid_tamperedSignature_returnsEmpty() {
        String generated = service.generate();
        String tampered = generated + "X";

        assertTrue(service.extractUuid(tampered).isEmpty());
    }

    @Test
    @DisplayName("extractUuid(): 구형 guest-xxxx 형태(dot 없음)는 empty를 반환한다")
    void extractUuid_legacyFormat_returnsEmpty() {
        assertTrue(service.extractUuid("guest-abc123def456").isEmpty());
    }

    @Test
    @DisplayName("extractUuid(): null은 empty를 반환한다")
    void extractUuid_null_returnsEmpty() {
        assertTrue(service.extractUuid(null).isEmpty());
    }

    @Test
    @DisplayName("extractUuid(): 공백 문자열은 empty를 반환한다")
    void extractUuid_blank_returnsEmpty() {
        assertTrue(service.extractUuid("   ").isEmpty());
    }

    @Test
    @DisplayName("extractUuid(): 다른 secret으로 서명한 값은 empty를 반환한다")
    void extractUuid_differentSecret_returnsEmpty() {
        GuestIdentifierService other = new GuestIdentifierService();
        ReflectionTestUtils.setField(other, "secret", "other-secret");

        String fromOther = other.generate();

        assertTrue(service.extractUuid(fromOther).isEmpty());
    }

    @Test
    @DisplayName("generate()를 두 번 호출하면 서로 다른 값을 반환한다")
    void generate_producesUniqueValues() {
        String first = service.generate();
        String second = service.generate();

        assertNotEquals(first, second);
    }
}
