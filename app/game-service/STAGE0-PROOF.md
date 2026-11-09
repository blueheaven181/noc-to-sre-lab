# Stage 0 proof: leaderboard unchanged after the Flyway switch

What this proves: `ddl-auto` went from `update` (Hibernate silently
creates/alters tables) to `validate` (Hibernate only checks the schema
matches, Flyway now owns it). This is meant to be a behavior-neutral
change. This is how to actually check that, not just assume it.

Run everything below from `app/game-service/` on a machine with Docker
installed - no local Java or Maven needed, the build happens inside the
container.

## 1. Start the stack

```
docker compose -f docker-compose.test.yml up --build -d
```

First run will take a few minutes (Maven pulling dependencies). Watch it
come up:

```
docker compose -f docker-compose.test.yml logs -f game-service
```

Look for, in order: a Flyway line like `Successfully applied 1 migration`,
then no Hibernate `SchemaManagementException` (that's the "validate" check
passing), then the normal Spring Boot `Started GameServiceApplication`
line. If the Flyway baseline/version math in `application.yml` is wrong,
or V1's SQL doesn't actually match what Hibernate used to generate, this
is where it'll fail loudly instead of silently - that's the point.

Once it's up, Ctrl+C out of the log stream (the stack keeps running).

## 2. Exercise the real path

This goes through the actual API, the actual RabbitMQ listener, and the
actual MySQL write - not a shortcut:

```
curl -X POST http://localhost:8080/api/session/complete \
  -H "Content-Type: application/json" \
  -d '{"username":"stage0-test-player","score":4242}'
```

Give it a couple seconds to process async, then:

```
curl http://localhost:8080/api/leaderboard
```

Expect to see `stage0-test-player` with `highScore: 4242` and
`"source":"redis-cache"` (the listener wrote to Redis when it handled the
event).

## 3. Prove the MySQL fallback path too

Clear Redis to force a cache miss, so `/api/leaderboard` has to fall back
to MySQL - the table Flyway now manages:

```
docker compose -f docker-compose.test.yml exec redis redis-cli FLUSHALL
curl http://localhost:8080/api/leaderboard
```

Expect the same player and score back, this time with
`"source":"mysql-fallback"`. If this matches step 2's numbers, the data
survived the round trip through the Flyway-managed table correctly.

## 4. Record the result

Paste the output of steps 1-3 back so it can go in the journal entry -
pass or fail, either way that's the actual record, not a guess.

## 5. Clean up

```
docker compose -f docker-compose.test.yml down -v
```

The `-v` drops the test database/volumes too - this stack is throwaway,
not something to keep running.
