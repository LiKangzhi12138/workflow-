package com.workflow.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SensitiveHttpLogSanitizer {

    static final String REDACTED = "******";
    private static final int MAX_LOG_BODY_CHARS = 4096;
    private static final int HASH_PREFIX_LENGTH = 12;

    private final ObjectMapper objectMapper;

    public SensitiveHttpLogSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sanitizeBody(byte[] body, URI uri, boolean requestBody) {
        if (body == null || body.length == 0) {
            return "";
        }
        return sanitizeJson(new String(body, StandardCharsets.UTF_8), uri, requestBody, body.length);
    }

    public String sanitizeText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return sanitizeJson(body, null, false, body.getBytes(StandardCharsets.UTF_8).length);
    }

    public Map<String, List<String>> sanitizeHeaders(HttpHeaders headers) {
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        headers.forEach((name, values) -> sanitized.put(
                name,
                isSensitiveName(name) ? List.of(REDACTED) : List.copyOf(values)
        ));
        return sanitized;
    }

    public String sanitizeUri(URI uri) {
        if (uri == null) {
            return "";
        }
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (Exception ignored) {
            return uri.getPath() == null ? "<uri omitted>" : uri.getPath();
        }
    }

    private String sanitizeJson(String body, URI uri, boolean requestBody, int bodyBytes) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (!parsed.isContainerNode()) {
                return "<scalar JSON body omitted; bytes=" + bodyBytes + ">";
            }
            JsonNode sanitized = redact(parsed.deepCopy());
            if (requestBody && isWeightsValidationJob(uri, sanitized)) {
                sanitized = summarizeWeightsValidationJob(sanitized);
            }
            return limit(objectMapper.writeValueAsString(sanitized));
        } catch (Exception ignored) {
            return "<non-json body omitted; bytes=" + bodyBytes + ">";
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.properties().forEach(entry -> {
                if (isSensitiveName(entry.getKey())) {
                    objectNode.put(entry.getKey(), REDACTED);
                } else {
                    redact(entry.getValue());
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::redact);
        }
        return node;
    }

    private boolean isWeightsValidationJob(URI uri, JsonNode body) {
        return uri != null
                && uri.getPath() != null
                && uri.getPath().endsWith("/internal/jobs")
                && "WEIGHTS_PROTOCOL_V1".equals(body.path("validationMode").asText());
    }

    private ObjectNode summarizeWeightsValidationJob(JsonNode body) {
        ObjectNode summary = objectMapper.createObjectNode();
        copy(summary, body, "workflowId");
        copy(summary, body, "jobId");
        copy(summary, body, "jobType");
        copy(summary, body, "validationMode");
        copy(summary, body, "datasetPath");
        copy(summary, body, "runtimeProfileId");

        JsonNode definition = body.path("trustedModelDefinition");
        copyAs(summary, definition, "definitionId", "definitionId");
        copyPrefixAs(summary, definition, "definitionSha256", "definitionShaPrefix");
        copyPrefixAs(summary, definition, "architectureSignature", "architectureSignaturePrefix");

        JsonNode weights = body.path("globalWeights");
        copyAs(summary, weights, "assetId", "assetId");
        copyAs(summary, weights, "weightsPath", "weightsPath");
        copyPrefixAs(summary, weights, "expectedSha256", "weightsShaPrefix");
        return summary;
    }

    private void copy(ObjectNode target, JsonNode source, String field) {
        copyAs(target, source, field, field);
    }

    private void copyAs(ObjectNode target, JsonNode source, String sourceField, String targetField) {
        JsonNode value = source.path(sourceField);
        if (!value.isMissingNode() && !value.isNull()) {
            target.set(targetField, value);
        }
    }

    private void copyPrefixAs(ObjectNode target, JsonNode source, String sourceField, String targetField) {
        JsonNode value = source.path(sourceField);
        if (value.isTextual() && !value.asText().isBlank()) {
            String text = value.asText();
            target.put(targetField, text.substring(0, Math.min(HASH_PREFIX_LENGTH, text.length())));
        }
    }

    private boolean isSensitiveName(String name) {
        String normalized = name == null
                ? ""
                : name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("apikey")
                || normalized.contains("aeskey")
                || normalized.contains("privatekey")
                || normalized.contains("accesskey");
    }

    private String limit(String value) {
        if (value.length() <= MAX_LOG_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_LOG_BODY_CHARS) + "...<truncated>";
    }
}
