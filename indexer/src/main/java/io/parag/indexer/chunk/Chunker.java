package io.parag.indexer.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document body into overlapping chunks for embedding.
 *
 * Strategy (see docs/notes/day-02): paragraph-aware greedy packing with a token
 * budget and a fixed token overlap between adjacent chunks.
 *
 *  - We split on blank lines first (paragraph boundaries) so we rarely cut a
 *    sentence in half; this beats naive fixed-width slicing on retrieval quality.
 *  - We greedily pack paragraphs until adding the next would exceed the budget,
 *    then start a new chunk that re-includes the tail of the previous one
 *    (overlap) so a fact spanning a boundary is still fully present in at least
 *    one chunk.
 *  - "Tokens" here are approximated as whitespace-delimited words. A real system
 *    uses the model's tokenizer; word-count is within ~25% for English and keeps
 *    the indexer dependency-free. We document this as a known approximation.
 */
@Component
public class Chunker {

    private final int chunkSizeTokens;
    private final int overlapTokens;

    public Chunker() {
        // Defaults mirror src/app/config.py so Java and Python stay aligned.
        this(350, 50);
    }

    public Chunker(int chunkSizeTokens, int overlapTokens) {
        if (overlapTokens >= chunkSizeTokens) {
            throw new IllegalArgumentException("overlap must be smaller than chunk size");
        }
        this.chunkSizeTokens = chunkSizeTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * @param body raw document text
     * @return ordered list of chunk strings; never null, may be empty for blank input
     */
    public List<String> chunk(String body) {
        List<String> chunks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return chunks;
        }

        String[] paragraphs = body.strip().split("\\n\\s*\\n");

        List<String> current = new ArrayList<>();
        int currentTokens = 0;

        for (String paragraph : paragraphs) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            int paraTokens = countTokens(para);

            // A single paragraph larger than the budget is hard-split by words.
            if (paraTokens > chunkSizeTokens) {
                if (!current.isEmpty()) {
                    chunks.add(String.join("\n\n", current));
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
                chunks.addAll(hardSplit(para));
                continue;
            }

            if (currentTokens + paraTokens > chunkSizeTokens && !current.isEmpty()) {
                chunks.add(String.join("\n\n", current));
                // Seed the next chunk with an overlap tail from what we just emitted.
                current = overlapTail(String.join("\n\n", current));
                currentTokens = countTokens(String.join("\n\n", current));
            }

            current.add(para);
            currentTokens += paraTokens;
        }

        if (!current.isEmpty()) {
            chunks.add(String.join("\n\n", current));
        }
        return chunks;
    }

    /** Approximate token count as whitespace-delimited words. */
    private int countTokens(String text) {
        if (text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    /** Take the last {@code overlapTokens} words of the emitted chunk as the seed of the next. */
    private List<String> overlapTail(String emitted) {
        List<String> seed = new ArrayList<>();
        if (overlapTokens <= 0) {
            return seed;
        }
        String[] words = emitted.strip().split("\\s+");
        int start = Math.max(0, words.length - overlapTokens);
        StringBuilder tail = new StringBuilder();
        for (int i = start; i < words.length; i++) {
            if (tail.length() > 0) {
                tail.append(' ');
            }
            tail.append(words[i]);
        }
        if (tail.length() > 0) {
            seed.add(tail.toString());
        }
        return seed;
    }

    /** Split an over-long paragraph into budget-sized, overlapping word windows. */
    private List<String> hardSplit(String para) {
        List<String> out = new ArrayList<>();
        String[] words = para.strip().split("\\s+");
        int step = chunkSizeTokens - overlapTokens;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(words.length, start + chunkSizeTokens);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(words[i]);
            }
            out.add(sb.toString());
            if (end == words.length) {
                break;
            }
        }
        return out;
    }
}
