-- Baseline migration for the casino-lab work (Stage 0).
--
-- This recreates the `players` table exactly as Hibernate's `ddl-auto:
-- update` had been creating it (see Player.java / the entity mapping) --
-- same columns, same types, same constraints. It is NOT a schema change.
-- The point of Stage 0 is proving that switching from Hibernate-managed
-- schema to Flyway-managed schema doesn't alter behavior -- this file is
-- the "before" shape, captured so Flyway can take over from here.
--
-- On a brand-new database (the Docker proof, and any future fresh
-- environment) this just creates the table. On the real lab's mysql01,
-- which already has this table, this file is never actually executed --
-- see spring.flyway.baseline-on-migrate / baseline-version in
-- application.yml, which tells Flyway "treat V1 as already applied" there.

CREATE TABLE players (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(255) NOT NULL,
    high_score      BIGINT       NOT NULL DEFAULT 0,
    sessions_played BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_players_username UNIQUE (username)
);
