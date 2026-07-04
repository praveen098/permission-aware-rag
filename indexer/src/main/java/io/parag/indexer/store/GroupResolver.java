package io.parag.indexer.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves group NAMES (as they arrive in events) to the integer group ids used
 * in chunks.allowed_groups.
 *
 * Events carry names ("engineering", "hr") so they're self-contained and decoupled
 * from database ids. But the chunks table filters on INT[] for a fast GIN index,
 * so the indexer translates once at write time.
 *
 * Group ids are effectively immutable, so we cache name->id. A name not in cache
 * triggers a single lookup; unknown groups are inserted (a new group appearing in
 * an event is a legitimate case).
 */
@Component
public class GroupResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public GroupResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolve a list of group names to ids, preserving order. */
    public List<Integer> resolve(List<String> names) {
        List<Integer> ids = new ArrayList<>(names.size());
        for (String name : names) {
            ids.add(resolveOne(name));
        }
        return ids;
    }

    private Integer resolveOne(String name) {
        Integer cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        List<Integer> found = jdbc.query(
                "SELECT id FROM groups WHERE name = ?",
                (rs, i) -> rs.getInt("id"),
                name);
        int id;
        if (!found.isEmpty()) {
            id = found.get(0);
        } else {
            // New group seen in an event: insert and use the generated id.
            id = jdbc.queryForObject(
                    "INSERT INTO groups (name) VALUES (?) "
                            + "ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name "
                            + "RETURNING id",
                    Integer.class, name);
        }
        cache.put(name, id);
        return id;
    }
}
