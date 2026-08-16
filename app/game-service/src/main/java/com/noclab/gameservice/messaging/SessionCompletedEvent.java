package com.noclab.gameservice.messaging;

import java.io.Serializable;

public class SessionCompletedEvent implements Serializable {
    private String username;
    private Long score;
    private String servedBy; // which jvmapp instance handled this — useful once nginx load-balances across both

    public SessionCompletedEvent() {}

    public SessionCompletedEvent(String username, Long score, String servedBy) {
        this.username = username;
        this.score = score;
        this.servedBy = servedBy;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getScore() { return score; }
    public void setScore(Long score) { this.score = score; }
    public String getServedBy() { return servedBy; }
    public void setServedBy(String servedBy) { this.servedBy = servedBy; }
}
