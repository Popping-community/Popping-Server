package com.example.popping.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Post extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Lob
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    private String guestNickname;

    private String guestPasswordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Board board;

    public void memberUpdate(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public void guestUpdate(String title, String content, String guestNickname, String guestPasswordHash) {
        this.title = title;
        this.content = content;
        this.guestNickname = guestNickname;
        this.guestPasswordHash = guestPasswordHash;
    }

    public boolean isAuthor(User user) {
        return author != null && user != null && author.getId().equals(user.getId());
    }

    public boolean isGuest() {
        return author == null;
    }
}
