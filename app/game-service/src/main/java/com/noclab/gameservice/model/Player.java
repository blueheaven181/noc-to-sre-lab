package com.noclab.gameservice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private Long highScore = 0L;

    @Column(nullable = false)
    private Long sessionsPlayed = 0L;

    public Player() {}

    public Player(String username) {
        this.username = username;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getHighScore() { return highScore; }
    public void setHighScore(Long highScore) { this.highScore = highScore; }
    public Long getSessionsPlayed() { return sessionsPlayed; }
    public void setSessionsPlayed(Long sessionsPlayed) { this.sessionsPlayed = sessionsPlayed; }
}
