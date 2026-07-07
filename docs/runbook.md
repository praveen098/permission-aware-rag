# Operations Runbook

Common local-dev issues and their one-command fixes. Everything here is a
lesson from Days 1-4 turned into a checklist.

## After a Mac restart, the stack won't come up

### Symptom
```
Error: Bind for 0.0.0.0:5432 failed: port is already allocated
```
`docker compose ps` shows no `parag-postgres`.

### Cause
Another Postgres container on the machine (typically from an unrelated project)
auto-restarted with Docker and grabbed port 5432 before you did. `KAFKA_AUTO_
CREATE_TOPICS_ENABLE` is intentionally off in our compose, so a fresh Postgres
in the wrong container would silently accept connections with different creds
and produce `password authentication failed for user "parag"`.

### Fix
```bash
docker ps | grep 5432
# note the container name that's squatting, e.g. dispatch-system-postgres-1
docker stop <that-container>
docker update --restart=no <that-container>   # so it stops coming back
cd ~/Desktop/PermissionAgent
docker compose up -d
sleep 25 && docker compose ps
```

## The topic disappeared after `docker compose down -v`

### Symptom
Indexer logs a stream of `UNKNOWN_TOPIC_OR_PARTITION` for `doc-events`.
`kafka-topics --list` doesn't show `doc-events`.

### Cause
`down -v` wipes Kafka's volume. `kafka-init` in compose is supposed to
recreate the topic on next `up`, but it's a one-shot container and can fail
silently (bad quoting, race with kafka health). The producer with default
config will *not* return an error - `produce()` queues locally and
"published" prints without the broker ever accepting the message.

### Fix
Create the topic explicitly (this is idempotent):
```bash
docker exec parag-kafka kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic doc-events \
  --partitions 3 --replication-factor 1

docker exec parag-kafka kafka-topics --bootstrap-server localhost:9092 --list
# doc-events must appear
```

### Prevention
`corpus_generator.py` now uses a delivery callback; a failed delivery
exits non-zero instead of lying. If it ever says "Delivered N/N", the broker
really acked them.

## Postgres password mismatch after volume reuse

### Symptom
`password authentication failed for user "parag"` even though the compose
sets `POSTGRES_PASSWORD: parag_dev`.

### Cause
Postgres only applies `POSTGRES_PASSWORD` when initializing an *empty* data
directory. A reused volume retains its original password, silently ignoring
compose changes.

### Fix
Confirm you're actually hitting your Postgres, not another one:
```bash
docker ps | grep parag-postgres
docker exec parag-postgres psql -U parag -c "SELECT 1;"
```
If `SELECT 1` fails but a *different* Postgres shows up on 5432, it's the
squatter case above. If it's genuinely a mismatch on your own volume:
```bash
docker compose down -v   # last resort - wipes data
docker compose up -d
# then re-create topic and re-run corpus_generator.py
```

## Java build error: `cannot access org.postgresql.util.PGBinaryObject`

### Cause
The pom had `<scope>runtime</scope>` on the postgresql driver, but pgvector's
Java bindings reference PGBinaryObject at compile time.

### Fix
Ensure the postgresql dependency in `indexer/pom.xml` has no `<scope>` block
(defaults to compile scope, which is what we need).

## Indexer starts but processes nothing

### Symptom
Spring Boot starts, no errors, but no "received DOC_CREATED" lines and no
new chunks in Postgres.

### Cause
The consumer group `parag-indexer` may have already committed offsets past
the end of the topic in a prior run. `auto-offset-reset: earliest` only
applies to a *new* group. Alternatively, the topic doesn't actually exist.

### Fix
Reset the group's offsets to the beginning:
```bash
docker exec parag-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group parag-indexer --topic doc-events \
  --reset-offsets --to-earliest --execute
```
(Requires the indexer to NOT be running while resetting.)
