package com.example.popping.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    @Column(length = 50)
    private String guestNickname;

    @Column(length = 255)
    private String guestPasswordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(nullable = false)
    private Long viewCount = 0L;

    @Column(nullable = false)
    private int commentCount = 0;

    @Column(nullable = false)
    private int likeCount = 0;

    @Column(nullable = false)
    private int dislikeCount = 0;

    private Post(String title, String content, User author,
                 String guestNickname, String guestPasswordHash, Board board) {
        this.title = title;
        this.content = content;
        this.author = author;
        this.guestNickname = guestNickname;
        this.guestPasswordHash = guestPasswordHash;
        this.board = board;
    }

    public static Post createMemberPost(String title, String content, User author, Board board) {
        validateCommon(title, content, board);
        if (author == null) throw new IllegalArgumentException("author는 필수입니다.");
        return new Post(title, content, author, null, null, board);
    }

    public static Post createGuestPost(String title, String content, String guestNickname, String guestPasswordHash, Board board) {
        validateCommon(title, content, board);
        if (guestNickname == null || guestNickname.isBlank())
            throw new IllegalArgumentException("guestNickname은 필수입니다.");
        if (guestPasswordHash == null || guestPasswordHash.isBlank())
            throw new IllegalArgumentException("guestPasswordHash는 필수입니다.");
        return new Post(title, content, null, guestNickname, guestPasswordHash, board);
    }

    private static void validateCommon(String title, String content, Board board) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title은 필수입니다.");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content는 필수입니다.");
        if (board == null) throw new IllegalArgumentException("board는 필수입니다.");
    }

    public void updateAsMember(String title, String content) {
        if (isGuest()) throw new IllegalStateException("게스트 게시글은 회원 수정 메서드를 사용할 수 없습니다.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title은 필수입니다.");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content는 필수입니다.");

        this.title = title;
        this.content = content;
    }

    public void updateAsGuest(String title, String content, String guestNickname) {
        if (!isGuest()) throw new IllegalStateException("회원 게시글은 게스트 수정 메서드를 사용할 수 없습니다.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title은 필수입니다.");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content는 필수입니다.");
        if (guestNickname == null || guestNickname.isBlank())
            throw new IllegalArgumentException("guestNickname은 필수입니다.");

        this.title = title;
        this.content = content;
        this.guestNickname = guestNickname;
    }

    public void changeGuestPasswordHash(String guestPasswordHash) {
        if (!isGuest()) throw new IllegalStateException("회원 게시글은 비밀번호가 없습니다.");
        if (guestPasswordHash == null || guestPasswordHash.isBlank())
            throw new IllegalArgumentException("guestPasswordHash는 필수입니다.");
        this.guestPasswordHash = guestPasswordHash;
    }

    public void increaseCommentCount() {
        this.commentCount++;
    }

    public void decreaseCommentCount() {
        if (this.commentCount > 0) this.commentCount--;
    }

    public boolean isAuthor(User user) {
        return author != null && user != null && author.getId().equals(user.getId());
    }

    public boolean isGuest() {
        return author == null;
    }
}
