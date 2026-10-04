package com.example.popping.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


// Counts and the reaction version change through bulk UPDATEs, outside any loaded entity.
// Writing only the changed columns keeps an edit's flush from putting old counts back.
@DynamicUpdate
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = {
        @Index(name = "idx_post_board", columnList = "board_id")
})
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

    // Moves in the same UPDATE as the counts, so a client can drop a like broadcast older than
    // the counts it already shows. The default fills existing rows when the column is added.
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private long reactionVersion = 0;

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

    public void decreaseCommentCount(int removedCount) {
        if (removedCount < 1 || removedCount > this.commentCount) {
            throw new IllegalStateException("삭제할 댓글 수와 게시글 댓글 수가 일치하지 않습니다.");
        }
        this.commentCount -= removedCount;
    }

    public boolean isAuthor(User user) {
        return author != null && user != null && author.getId().equals(user.getId());
    }

    public boolean isGuest() {
        return author == null;
    }
}
