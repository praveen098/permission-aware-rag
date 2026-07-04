package io.parag.indexer.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client for the Python embedding service (POST /embed).
 *
 * Why a separate service instead of embedding in-process (see docs/notes/day-02 D2):
 *  - sentence-transformers is a Python ecosystem; there's no clean Java equivalent
 *    without dragging ONNX runtime into the build.
 *  - the embedding tier is CPU/GPU-heavy and the consumer is I/O-heavy; splitting
 *    them lets each scale independently. This is how production RAG systems are
 *    typically structured.
 *
 * Uses the JDK's built-in HttpClient - no web-server dependency pulled into the
 * indexer.
 */
@Component
public class EmbeddingClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final int dim;

    public EmbeddingClient(
            @Value("${parag.embedding.base-url}") String baseUrl,
            @Value("${parag.embedding.dim}") int dim) {
        this.baseUrl = baseUrl;
        this.dim = dim;
    }

    /**
     * Embed a batch of texts. Order of the returned vectors matches the input.
     *
     * @throws EmbeddingException on transport error, non-200, or dimension mismatch
     */
    public List<float[]> embed(List<String> texts) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode arr = payload.putArray("texts");
            for (String t : texts) {
                arr.add(t);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "embedding service returned HTTP " + response.statusCode()
                                + ": " + response.body());
            }

            ObjectNode body = (ObjectNode) mapper.readTree(response.body());
            ArrayNode vectors = (ArrayNode) body.get("embeddings");
            List<float[]> result = new java.util.ArrayList<>(vectors.size());
            for (int i = 0; i < vectors.size(); i++) {
                ArrayNode vec = (ArrayNode) vectors.get(i);
                if (vec.size() != dim) {
                    throw new EmbeddingException(
                            "expected dim " + dim + " but got " + vec.size());
                }
                float[] f = new float[dim];
                for (int j = 0; j < dim; j++) {
                    f[j] = (float) vec.get(j).asDouble();
                }
                result.add(f);
            }
            return result;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("failed to call embedding service", e);
        }
    }

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}
