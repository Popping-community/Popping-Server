package com.example.popping.common;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class HtmlSanitizer {

    private static final Safelist CONTENT_SAFELIST = Safelist.relaxed()
            // 이미지 허용
            .addTags("img")
            .addAttributes("img", "src", "alt", "title")
            .addProtocols("img", "src", "http", "https");

    public String sanitize(String input) {
        if (input == null) return null;
        return Jsoup.clean(input, CONTENT_SAFELIST);
    }
}
