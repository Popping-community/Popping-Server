package com.example.popping.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.constant.SessionConst;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.BoardCreateRequest;
import com.example.popping.dto.BoardResponse;
import com.example.popping.dto.BoardUpdateRequest;
import com.example.popping.dto.PostResponse;
import com.example.popping.service.BoardService;
import com.example.popping.service.PostService;

@Controller
@RequiredArgsConstructor
@RequestMapping("/boards")
public class BoardController {

    public static final String REDIRECT_BOARDS = "redirect:/boards";
    private final BoardService boardService;
    private final PostService postService;

    @GetMapping
    public String listBoards(Model model) {
        List<BoardResponse> boards = boardService.getAllBoards();
        model.addAttribute("boards", boards);
        return "board/list";
    }

    @GetMapping("/{slug}")
    public String getBoard(@PathVariable String slug, Model model) {
        BoardResponse boardResponse = boardService.getBoard(slug);
        List<PostResponse> postResponses = postService.getPostsByBoardSlug(slug);
        model.addAttribute("board", boardResponse);
        model.addAttribute("posts", postResponses);
        return "board/detail";
    }

    @GetMapping("/new")
    public String newBoardForm(@ModelAttribute BoardCreateRequest boardCreateRequest) {
        return "board/form";
    }

    @PostMapping
    public String createBoard(@Valid @ModelAttribute BoardCreateRequest dto,
                              BindingResult bindingResult,
                              @AuthenticationPrincipal UserPrincipal loginUser) {
        if (bindingResult.hasErrors()) {
            return "board/form";
        }
        String slug = boardService.createBoard(dto, loginUser);
        return REDIRECT_BOARDS + "/" + slug;
    }

    @GetMapping("/{slug}/edit")
    public String editBoardForm(@ModelAttribute BoardUpdateRequest boardUpdateRequest,
                                @AuthenticationPrincipal UserPrincipal loginUser,
                                @PathVariable String slug, Model model) {
        BoardResponse dto = boardService.getBoardForEdit(slug, loginUser);
        model.addAttribute("form", dto);
        return "board/edit-form";
    }

    @PostMapping("/{slug}/edit")
    public String updateBoard(@PathVariable String slug,
                              @AuthenticationPrincipal UserPrincipal loginUser,
                              @Valid @ModelAttribute BoardUpdateRequest dto,
                              BindingResult bindingResult,
                              Model model) {
        if (bindingResult.hasErrors()) {
            BoardResponse boardResponse = boardService.getBoardForEdit(slug, loginUser);
            model.addAttribute("form", boardResponse);
            return "board/edit-form";
        }
        boardService.updateBoard(slug, dto, loginUser);
        return REDIRECT_BOARDS + "/" + slug;
    }

    @PostMapping("/{slug}/delete")
    public String deleteBoard(@PathVariable String slug,
                              @AuthenticationPrincipal UserPrincipal loginUser) {
        boardService.deleteBoard(slug, loginUser);
        return REDIRECT_BOARDS;
    }
}