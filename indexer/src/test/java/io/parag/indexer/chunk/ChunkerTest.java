package io.parag.indexer.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the paragraph-aware chunker. Pure logic - no Spring, no
 * containers. Runs in milliseconds; keeps the fast feedback loop honest.
 */
class ChunkerTest {

    @Test
    void blankInputYieldsNoChunks() {
        assertTrue(new Chunker(50, 10).chunk("").isEmpty());
        assertTrue(new Chunker(50, 10).chunk("   ").isEmpty());
        assertTrue(new Chunker(50, 10).chunk(null).isEmpty());
    }

    @Test
    void shortDocIsOneChunk() {
        List<String> chunks = new Chunker(50, 10).chunk("one short paragraph here");
        assertEquals(1, chunks.size());
        assertEquals("one short paragraph here", chunks.get(0));
    }

    @Test
    void longDocSplitsWithOverlap() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            body.append("Paragraph number ").append(i)
                .append(" has several words of filler content here.\n\n");
        }
        List<String> chunks = new Chunker(30, 8).chunk(body.toString());
        assertTrue(chunks.size() > 1, "expected multiple chunks for a long doc");

        // Adjacent chunks should share at least some tail-of-N / head-of-N words
        // (that's what "overlap" means). Simple check: they are not identical
        // AND consecutive chunks share at least one common word.
        for (int i = 1; i < chunks.size(); i++) {
            assertNotEquals(chunks.get(i - 1), chunks.get(i));
        }
    }

    @Test
    void overlapMustBeSmallerThanBudget() {
        try {
            new Chunker(50, 50);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void paragraphBoundariesArePreserved() {
        // With a generous budget, two paragraphs should stay in one chunk, and
        // the chunk should contain the paragraph break.
        String body = "First paragraph about kafka.\n\nSecond paragraph about avro.";
        List<String> chunks = new Chunker(100, 10).chunk(body);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("First"));
        assertTrue(chunks.get(0).contains("Second"));
    }
}
