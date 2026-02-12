package com.example.popping.config.web;

import java.beans.PropertyEditorSupport;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import lombok.RequiredArgsConstructor;

import com.example.popping.common.HtmlSanitizer;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalBindingConfig {

    private final HtmlSanitizer htmlSanitizer;

    @InitBinder
    public void initBinder(WebDataBinder binder) {

        binder.registerCustomEditor(String.class, new PropertyEditorSupport() {

            @Override
            public void setAsText(String text) {
                setValue(htmlSanitizer.sanitize(text));
            }
        });
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            builder.deserializerByType(String.class, new HtmlSanitizingDeserializer(htmlSanitizer));
        };
    }
}

