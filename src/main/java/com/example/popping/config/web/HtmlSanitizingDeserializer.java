package com.example.popping.config.web;

import java.io.IOException;

import lombok.RequiredArgsConstructor;

import com.example.popping.common.HtmlSanitizer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

@RequiredArgsConstructor
public class HtmlSanitizingDeserializer extends JsonDeserializer<String> {

    private final HtmlSanitizer htmlSanitizer;

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getValueAsString();
        if (value == null) {
            return null;
        }
        return htmlSanitizer.sanitize(value);
    }
}