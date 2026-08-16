package com.noclab.gameservice.controller;

import com.noclab.gameservice.messaging.SessionCompletedEvent;
import com.noclab.gameservice.messaging.SessionEventListener;
import com.noclab.gameservice.messaging.SessionEventPublisher;
import com.noclab.gameservice.model.Player;
import com.noclab.gameservice.repository.PlayerRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class GameController {

    private final SessionEventPublisher publisher;
    private final PlayerRepository playerRepository;
    private final StringRedisTemplate redisTemplate;
    private final String hostname;

    public GameController(SessionEventPublisher publisher,
                           PlayerRepository playerRepository,
                           StringRedisTemplate redisTemplate) {
        this.publisher = publisher;
        this.playerRepository = playerRepository;
        this.redisTemplate = redisTemplate;
        this.hostname = resolveHostname();
    }

    public record SessionCompleteRequest(String username, Long score) {}

    @PostMapping("/session/complete")
    public ResponseEntity<Map<String, Object>> completeSession(@RequestBody SessionCompleteRequest req) {
        publisher.publishSessionCompleted(
                new SessionCompletedEvent(req.username(), req.score(), hostname));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("servedBy", hostname);
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<Map<String, Object>>> leaderboard(
            @RequestParam(defaultValue = "10") int top) {

        Set<ZSetOperations.TypedTuple<String>> cached = redisTemplate.opsForZSet()
                .reverseRangeWithScores(SessionEventListener.LEADERBOARD_KEY, 0, top - 1);

        if (cached == null || cached.isEmpty()) {
            // Cache miss/cold start — fall back to MySQL and repopulate Redis
            List<Player> topPlayers = playerRepository.findTopPlayers();
            topPlayers.stream().limit(top).forEach(p ->
                    redisTemplate.opsForZSet().add(SessionEventListener.LEADERBOARD_KEY,
                            p.getUsername(), p.getHighScore()));

            return ResponseEntity.ok(topPlayers.stream().limit(top).map(p -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("username", p.getUsername());
                row.put("highScore", p.getHighScore());
                row.put("source", "mysql-fallback");
                return row;
            }).toList());
        }

        return ResponseEntity.ok(cached.stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", t.getValue());
            row.put("highScore", t.getScore());
            row.put("source", "redis-cache");
            return row;
        }).toList());
    }

    @GetMapping("/whoami")
    public ResponseEntity<Map<String, String>> whoami() {
        return ResponseEntity.ok(Map.of("servedBy", hostname));
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
