package com.example.popping.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

import com.example.popping.constant.SessionConst;
import com.example.popping.domain.User;
import com.example.popping.dto.PostCreateRequest;
import com.example.popping.dto.PostResponse;
import com.example.popping.dto.PostUpdateRequest;
import com.example.popping.service.PostService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/boards/{slug}")
public class PostController {

    private final PostService postService;

    @GetMapping("/{postId}")
    public String getPost(@PathVariable String slug,
                          @PathVariable Long postId,
                          Model model) {
        PostResponse dto = postService.getPost(postId);
        model.addAttribute("post", dto);
        model.addAttribute("slug", slug);
        return "post/detail";
    }

    @GetMapping("/new")
    public String newPostForm(@ModelAttribute PostCreateRequest postCreateRequest,
                              @PathVariable String slug,
                              Model model,
                              @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser) {
        model.addAttribute("slug", slug);
        return "post/form";
    }

    @PostMapping
    public String createPost(@PathVariable String slug,
                             @ModelAttribute PostCreateRequest dto,
                             @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser) {
        Long postId = postService.createPost(slug, dto, loginUser);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @GetMapping("/{postId}/edit")
    public String editPostForm(@ModelAttribute PostUpdateRequest postUpdateRequest,
                               @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser,
                               @PathVariable String slug,
                               @PathVariable Long postId,
                               Model model) {
        PostResponse dto = postService.getPostForEdit(postId, loginUser);
        model.addAttribute("form", dto);
        model.addAttribute("slug", slug);
        return "post/edit-form";
    }

    @PostMapping("/{postId}/edit")
    public String updatePost(@SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser,
                             @PathVariable String slug,
                             @PathVariable Long postId,
                             @ModelAttribute PostUpdateRequest dto) {
        postService.updatePost(postId, dto, loginUser);
        return "redirect:/boards/" + slug + "/" + postId;
    }

    @PostMapping("/{postId}/delete")
    public String deletePost(@SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser,
                             @PathVariable String slug,
                             @PathVariable Long postId) {
        postService.deletePost(postId, loginUser);
        return "redirect:/boards/" + slug;
    }
}
