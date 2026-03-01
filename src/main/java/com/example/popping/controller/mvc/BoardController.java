package com.example.popping.controller.mvc;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.BoardCreateRequest;
import com.example.popping.dto.BoardUpdateRequest;
import com.example.popping.service.BoardService;
import com.example.popping.service.PostService;

@Controller
@RequiredArgsConstructor
@RequestMapping(BoardController.BASE_PATH)
public class BoardController {

    static final String BASE_PATH = "/boards";

    private static final String VIEW_LIST = "board/list";
    private static final String VIEW_DETAIL = "board/detail";
    private static final String VIEW_FORM = "board/form";
    private static final String VIEW_EDIT_FORM = "board/edit-form";

    private final BoardService boardService;
    private final PostService postService;

    @GetMapping
    public String listBoards(Model model) {
        model.addAttribute("boards", boardService.getAllBoards());
        return VIEW_LIST;
    }

    @GetMapping("/{slug}")
    public String getBoard(@PathVariable String slug, Model model) {
        putBoardDetail(model, slug);
        return VIEW_DETAIL;
    }

    @GetMapping("/new")
    public String newBoardForm(@ModelAttribute("form") BoardCreateRequest form) {
        return VIEW_FORM;
    }

    @PostMapping
    public String createBoard(@Valid @ModelAttribute("form") BoardCreateRequest form,
                              BindingResult bindingResult,
                              @AuthenticationPrincipal UserPrincipal loginUser) {
        if (bindingResult.hasErrors()) {
            return VIEW_FORM;
        }

        String slug = boardService.createBoard(form, loginUser);
        return redirectToBoard(slug);
    }

    @GetMapping("/{slug}/edit")
    public String editBoardForm(@PathVariable String slug,
                                @AuthenticationPrincipal UserPrincipal loginUser,
                                @ModelAttribute("form") BoardUpdateRequest form,
                                Model model) {
        putEditForm(model, slug, loginUser);
        return VIEW_EDIT_FORM;
    }

    @PostMapping("/{slug}/edit")
    public String updateBoard(@PathVariable String slug,
                              @AuthenticationPrincipal UserPrincipal loginUser,
                              @Valid @ModelAttribute("form") BoardUpdateRequest form,
                              BindingResult bindingResult,
                              Model model) {
        if (bindingResult.hasErrors()) {
            putEditForm(model, slug, loginUser);
            return VIEW_EDIT_FORM;
        }

        boardService.updateBoard(slug, form, loginUser);
        return redirectToBoard(slug);
    }

    @PostMapping("/{slug}/delete")
    public String deleteBoard(@PathVariable String slug,
                              @AuthenticationPrincipal UserPrincipal loginUser) {
        boardService.deleteBoard(slug, loginUser);
        return redirectToBoards();
    }

    private void putBoardDetail(Model model, String slug) {
        model.addAttribute("board", boardService.getBoardResponse(slug));
        model.addAttribute("posts", postService.getPostsByBoardSlug(slug));
    }

    private void putEditForm(Model model, String slug, UserPrincipal loginUser) {
        model.addAttribute("form", boardService.getBoardForEdit(slug, loginUser));
    }

    private String redirectToBoards() {
        return "redirect:" + BASE_PATH;
    }

    private String redirectToBoard(String slug) {
        return "redirect:" + BASE_PATH + "/" + slug;
    }
}