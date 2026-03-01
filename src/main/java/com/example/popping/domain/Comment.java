package com.example.popping.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    @Column(length = 50)
    private String guestNickname;

    @Column(length = 255)
    private String guestPasswordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Comment parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<Comment> children = new ArrayList<>();

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private int likeCount = 0;

    @Column(nullable = false)
    private int dislikeCount = 0;

    private Comment(String content, User author, String guestNickname,
            String guestPasswordHash, Post post, Comment parent, int depth) {
        this.content = content;
        this.author = author;
        this.guestNickname = guestNickname;
        this.guestPasswordHash = guestPasswordHash;
        this.post = post;
        this.parent = parent;
        this.depth = depth;
    }

    public static Comment createMemberComment(String content, User author, Post post, Comment parent) {
        validateCommon(content, post);
        if (author == null) throw new IllegalArgumentException("author는 필수입니다.");
        return new Comment(content, author, null, null, post, parent, calcDepth(parent));
    }

    public static Comment createGuestComment(String content, String guestNickname, String guestPasswordHash, Post post, Comment parent) {
        validateCommon(content, post);
        if (guestNickname == null || guestNickname.isBlank())
            throw new IllegalArgumentException("guestNickname은 필수입니다.");
        if (guestPasswordHash == null || guestPasswordHash.isBlank())
            throw new IllegalArgumentException("guestPasswordHash는 필수입니다.");
        return new Comment(content, null, guestNickname, guestPasswordHash, post, parent, calcDepth(parent));
    }

    private static void validateCommon(String content, Post post) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content는 필수입니다.");
        if (post == null) throw new IllegalArgumentException("post는 필수입니다.");
    }

    private static int calcDepth(Comment parent) {
        return parent != null ? parent.getDepth() + 1 : 0;
    }

    public boolean isAuthor(User user) {
        return author != null && user != null && author.getId().equals(user.getId());
    }

    public boolean isGuest() {
        return author == null;
    }

    public boolean isReply() {
        return parent != null;
    }
}
