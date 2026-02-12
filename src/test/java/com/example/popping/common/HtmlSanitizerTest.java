package com.example.popping.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HtmlSanitizerTest {

    private HtmlSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new HtmlSanitizer();
    }

    @Test
    void testRemoveScriptTag() {
        String malicious = "<script>alert('xss')</script>Hello";
        String expected = "Hello";

        String result = sanitizer.sanitize(malicious);

        Assertions.assertEquals(expected, result);
    }

    @Test
    void testStripAllHtmlTags() {
        String input = "<p>text</p><b>strong</b>";
        String expected = "textstrong";

        String result = sanitizer.sanitize(input);

        Assertions.assertEquals(expected, result);
    }

    @Test
    void testNullInput() {
        Assertions.assertNull(sanitizer.sanitize(null));
    }

    @Test
    void testOnAttributeInjection() {
        String malicious = "<img src=x onerror=alert(1) />";
        String expected = "";

        String result = sanitizer.sanitize(malicious);
        Assertions.assertEquals(expected, result);
    }
}

