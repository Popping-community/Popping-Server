package com.example.popping.config.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.popping.common.HtmlSanitizer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class HtmlSanitizingDeserializerTest {

    private HtmlSanitizingDeserializer deserializer;

    @Mock
    private HtmlSanitizer htmlSanitizer;

    @Mock
    private JsonParser jsonParser;

    @Mock
    private DeserializationContext context;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        deserializer = new HtmlSanitizingDeserializer(htmlSanitizer);
    }

    @Test
    @DisplayName("HTML 태그가 포함된 문자열을 소독해야 한다")
    void deserialize_withHtml_sanitizesValue() throws Exception {
        // Given
        String dirtyHtml = "<script>alert('xss')</script><p>Hello</p>";
        String cleanHtml = "<p>Hello</p>";
        when(jsonParser.getValueAsString()).thenReturn(dirtyHtml);
        when(htmlSanitizer.sanitize(dirtyHtml)).thenReturn(cleanHtml);

        // When
        String result = deserializer.deserialize(jsonParser, context);

        // Then
        assertEquals(cleanHtml, result);
        verify(htmlSanitizer, times(1)).sanitize(dirtyHtml);
    }

    @Test
    @DisplayName("null 입력 시 null을 반환해야 한다 (커버리지 포인트)")
    void deserialize_withNull_returnsNull() throws Exception {
        // Given
        when(jsonParser.getValueAsString()).thenReturn(null);

        // When
        String result = deserializer.deserialize(jsonParser, context);

        // Then
        assertNull(result);
        // value가 null이므로 sanitizer는 호출되지 않아야 함
        verify(htmlSanitizer, never()).sanitize(anyString());
    }

    @Test
    @DisplayName("일반 문자열은 그대로 반환되어야 한다")
    void deserialize_withNormalString_returnsAsIs() throws Exception {
        // Given
        String normalText = "Normal Text";
        when(jsonParser.getValueAsString()).thenReturn(normalText);
        when(htmlSanitizer.sanitize(normalText)).thenReturn(normalText);

        // When
        String result = deserializer.deserialize(jsonParser, context);

        // Then
        assertEquals(normalText, result);
    }
}
