package com.example.popping.controller;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.constant.SessionConst;
import com.example.popping.domain.User;
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
    public String getBoard(@SessionAttribute(name = SessionConst.LOGIN_USER, required = false) User loginUser,
                           @PathVariable String slug, Model model) {
        BoardResponse dto = boardService.getBoard(slug);
        List<PostResponse> posts = postService.getPostsByBoardSlug(slug);
        model.addAttribute("board", dto);
        model.addAttribute("posts", posts);
        model.addAttribute("loginUser", loginUser);
        return "board/detail";
    }

    @GetMapping("/new")
    public String newBoardForm(@ModelAttribute BoardCreateRequest boardCreateRequest,
                               @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser) {
        return "board/form";
    }

    @PostMapping
    public String createBoard(@Valid @ModelAttribute BoardCreateRequest dto,
                              BindingResult bindingResult,
                              @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser) {
        if(bindingResult.hasErrors()){
            return "board/form";
        }
        String slug = boardService.createBoard(dto, loginUser);
        return REDIRECT_BOARDS + "/" + slug;
    }

    @GetMapping("/{slug}/edit")
    public String editBoardForm(@ModelAttribute BoardUpdateRequest boardUpdateRequest,
                                @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser,
                                @PathVariable String slug, Model model) {
        BoardResponse dto = boardService.getBoardForEdit(slug, loginUser);
        model.addAttribute("form", dto);
        return "board/edit-form";
    }

    @PostMapping("/{slug}/edit")
    public String updateBoard(@PathVariable String slug,
                              @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser,
                              @Valid @ModelAttribute BoardUpdateRequest dto,
                              BindingResult bindingResult,
                              Model model) {
        if(bindingResult.hasErrors()){
            BoardResponse boardResponse = boardService.getBoardForEdit(slug, loginUser);
            model.addAttribute("form", boardResponse);
            return "board/edit-form";
        }
        boardService.updateBoard(slug, dto, loginUser);
        return REDIRECT_BOARDS;
    }

    @PostMapping("/{slug}/delete")
    public String deleteBoard(@PathVariable String slug,
                              @SessionAttribute(name = SessionConst.LOGIN_USER, required = true) User loginUser) {
        boardService.deleteBoard(slug, loginUser);
        return REDIRECT_BOARDS;
    }
}
