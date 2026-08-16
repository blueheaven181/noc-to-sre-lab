package com.noclab.gameservice.messaging;

import com.noclab.gameservice.config.RabbitMQConfig;
import com.noclab.gameservice.model.Player;
import com.noclab.gameservice.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(SessionEventListener.class);
    public static final String LEADERBOARD_KEY = "leaderboard";

    private final PlayerRepository playerRepository;
    private final StringRedisTemplate redisTemplate;

    public SessionEventListener(PlayerRepository playerRepository, StringRedisTemplate redisTemplate) {
        this.playerRepository = playerRepository;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void handleSessionCompleted(SessionCompletedEvent event) {
        log.info("Session completed: user={} score={} servedBy={}",
                event.getUsername(), event.getScore(), event.getServedBy());

        // Source of truth: MySQL
        Player player = playerRepository.findByUsername(event.getUsername())
                .orElseGet(() -> new Player(event.getUsername()));

        player.setSessionsPlayed(player.getSessionsPlayed() + 1);
        if (event.getScore() > player.getHighScore()) {
            player.setHighScore(event.getScore());
        }
        playerRepository.save(player);

        // Fast-read cache: Redis sorted set, score = high score
        redisTemplate.opsForZSet().add(LEADERBOARD_KEY, event.getUsername(), player.getHighScore());
    }
}
