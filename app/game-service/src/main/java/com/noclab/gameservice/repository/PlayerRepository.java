package com.noclab.gameservice.repository;

import com.noclab.gameservice.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);

    @Query("SELECT p FROM Player p ORDER BY p.highScore DESC")
    List<Player> findTopPlayers();
}
