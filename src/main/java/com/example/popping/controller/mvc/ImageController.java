package com.example.popping.controller.mvc;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

import com.example.popping.dto.ImageResponse;
import com.example.popping.service.ImageService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/images")
public class ImageController {

    private final ImageService imageService;

    @PostMapping("/upload")
    @ResponseBody
    public ImageResponse uploadImage(@RequestParam("image") MultipartFile file) {
        return imageService.uploadAndCreateTempImage(file);
    }
}
