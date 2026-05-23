/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.jobs.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Synthesises the JSON config that opencode reads at launch time.
 *
 * <p>The config combines four inputs:</p>
 * <ul>
 *   <li><b>Provider URL</b> from {@code OPENCODE_PROVIDER_URL} (default
 *       {@code http://localhost:8084/v1} — the llama.cpp
 *       {@code llama-server} endpoint launched by
 *       {@code tools/bin/llama.sh}).</li>
 *   <li><b>API key</b> from {@code OPENCODE_API_KEY} (default empty; local
 *       servers do not require auth).</li>
 *   <li><b>Model</b> from the request, falling back to
 *       {@code OPENCODE_DEFAULT_MODEL} and ultimately the literal string
 *       {@code "default"}. Local OpenAI-compatible servers (notably
 *       llama.cpp's {@code llama-server}) ignore the model field on the
 *       wire and serve whichever GGUF was loaded, so a stable, model-agnostic
 *       alias removes the need to revise this code every time the operator
 *       swaps weights.</li>
 *   <li><b>MCP servers</b> translated from the orchestrator's canonical
 *       {@code {"mcpServers":{...}}} JSON.</li>
 *   <li><b>Permissions / allowlist</b> translated from the orchestrator's CSV
 *       allowlist; built-in entries become tool toggles, and
 *       {@code mcp__server__tool} entries become per-server grants.</li>
 * </ul>
 *
 * <p>The exact shape of opencode's permission and MCP blocks is evolving. The
 * builder isolates each translation in a single method so the shape can be
 * adjusted without touching the runner.</p>
 */
final class OpencodeConfigBuilder {

    /** Default OpenAI-compatible endpoint URL when {@link #ENV_PROVIDER_URL} is unset. */
    static final String DEFAULT_PROVIDER_URL = "http://localhost:8084/v1";

    /**
     * Default model name when neither the request nor {@link #ENV_DEFAULT_MODEL}
     * supplies one. The literal string {@code "default"} is used deliberately
     * so that the project does not embed a specific model identifier in code;
     * local OpenAI-compatible providers (llama.cpp's {@code llama-server})
     * ignore the model field on the wire and respond with whichever GGUF was
     * loaded at launch.
     */
    static final String FALLBACK_MODEL = "default";

    /** Conventional logical identifier used for the local provider in opencode's config. */
    static final String PROVIDER_ID = "local";

    /** Environment variable that overrides the provider URL. */
    static final String ENV_PROVIDER_URL = "OPENCODE_PROVIDER_URL";

    /** Environment variable that supplies the API key (defaults to empty). */
    static final String ENV_API_KEY = "OPENCODE_API_KEY";

    /** Environment variable that supplies the fallback model name. */
    static final String ENV_DEFAULT_MODEL = "OPENCODE_DEFAULT_MODEL";

    /** Built-in tool names recognised when translating the allowlist. */
    private static final List<String> BUILTIN_TOOLS =
            List.of("Read", "Edit", "Bash", "Glob", "Grep", "Write");

    /** Shared JSON mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Environment lookup; overridable for tests. */
    private final Function<String, String> envLookup;

    /** Constructs a builder that reads the live process environment. */
    OpencodeConfigBuilder() {
        this(System::getenv);
    }

    /**
     * Constructs a builder with the supplied environment accessor.
     *
     * @param envLookup returns the value of an environment variable, or null
     */
    OpencodeConfigBuilder(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    /**
     * Backwards-compatible no-argument form. Returns the live provider URL,
     * defaulting to the local llama-server endpoint when no override is set.
     *
     * @return the OpenAI-compatible endpoint URL
     */
    String resolveProviderUrl() {
        return resolveProviderUrl(DEFAULT_PROVIDER_URL);
    }

    /**
     * Resolves the provider URL from {@link #ENV_PROVIDER_URL}, falling back to
     * {@code defaultUrl} when the variable is absent or empty.
     *
     * <p>Backwards-compatible single-argument form. Assumes the {@link #PROVIDER_ID
     * local} provider — the env override applies and {@code defaultUrl} is the
     * local llama-server endpoint. For non-local providers use
     * {@link #resolveProviderUrl(String, String)} so the canonical cloud URL
     * is not clobbered by a local-only env override.</p>
     *
     * @param defaultUrl the default URL to use when {@link #ENV_PROVIDER_URL} is not set
     * @return the OpenAI-compatible endpoint URL
     */
    String resolveProviderUrl(String defaultUrl) {
        return resolveProviderUrl(PROVIDER_ID, defaultUrl);
    }

    /**
     * Resolves the provider URL for {@code provider}. The {@link #ENV_PROVIDER_URL}
     * override applies only to the {@link #PROVIDER_ID local} provider — its name
     * dates to a time when the agent only spoke to local llama-server / Ollama
     * instances, and applying it to {@code openrouter} or {@code anthropic} would
     * silently redirect cloud traffic back to whatever the agent container points
     * at locally (the original symptom of this bug: {@code Provider: openrouter}
     * paired with {@code Provider URL: http://mac-studio:8084/v1}).
     *
     * @param provider   the provider name, or {@code null}/empty for the local default
     * @param defaultUrl the canonical URL for {@code provider} (e.g. the entry
     *                   from {@code OpencodeRunner.PROVIDER_MAP})
     * @return the OpenAI-compatible endpoint URL
     */
    String resolveProviderUrl(String provider, String defaultUrl) {
        boolean isLocal = provider == null || provider.isEmpty()
                || PROVIDER_ID.equals(provider);
        if (isLocal) {
            String value = envLookup.apply(ENV_PROVIDER_URL);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return defaultUrl;
    }

    /**
     * Backwards-compatible no-argument form. Reads the API key from the
     * legacy {@link #ENV_API_KEY} only (no provider-specific fallback).
     *
     * @return the API key string (possibly empty)
     */
    String resolveApiKey() {
        String value = envLookup.apply(ENV_API_KEY);
        return value == null ? "" : value;
    }

    /**
     * Resolves the API key from {@link #ENV_API_KEY}, a workspace secret, or a
     * provider-specific environment variable. The secret name and env var name come
     * from {@code OpencodeRunner.PROVIDER_MAP} — the canonical table — so this method
     * never re-encodes the per-provider mapping itself.
     *
     * @param secretName   workspace secret name for this provider, or null if none
     * @param envVarName   provider-specific environment variable name, or null if none
     * @param secretLookup optional workspace secret lookup function
     * @return the API key string (possibly empty)
     */
    String resolveApiKey(String secretName, String envVarName, Function<String, String> secretLookup) {
        String value = envLookup.apply(ENV_API_KEY);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (secretLookup != null && secretName != null) {
            String secretValue = secretLookup.apply(secretName);
            if (secretValue != null && !secretValue.isEmpty()) {
                return secretValue;
            }
        }
        if (envVarName != null) {
            String envValue = envLookup.apply(envVarName);
            if (envValue != null && !envValue.isEmpty()) {
                return envValue;
            }
        }
        return "";
    }

    /**
     * Resolves the model identifier: request takes priority, then
     * {@link #ENV_DEFAULT_MODEL}, then {@link #FALLBACK_MODEL}.
     *
     * @param requestModel the model from {@link AgentRunRequest#getModel()}
     * @return the resolved model name; never empty
     */
    String resolveModel(String requestModel) {
        if (requestModel != null && !requestModel.isEmpty()) {
            return requestModel;
        }
        String envModel = envLookup.apply(ENV_DEFAULT_MODEL);
        if (envModel != null && !envModel.isEmpty()) {
            return envModel;
        }
        return FALLBACK_MODEL;
    }

    /**
     * Backwards-compatible single-argument form. Uses the default provider
     * id ({@link #PROVIDER_ID}, "local") when no provider is supplied.
     *
     * @param requestModel the model from {@link AgentRunRequest#getModel()}
     * @return the qualified identifier (e.g. {@code "local/default"})
     */
    String resolveQualifiedModel(String requestModel) {
        return resolveQualifiedModel(null, requestModel);
    }

    /**
     * Returns the qualified model identifier in opencode's
     * {@code provider/model} form.
     *
     * @param provider     provider identifier (local, openrouter, anthropic)
     * @param requestModel the model from {@link AgentRunRequest#getModel()}
     * @return the qualified identifier (e.g. {@code "openrouter/qwen3-coder:exacto"})
     */
    String resolveQualifiedModel(String provider, String requestModel) {
        String effectiveProvider = (provider == null || provider.isEmpty()) ? PROVIDER_ID : provider;
        return effectiveProvider + "/" + resolveModel(requestModel);
    }

    /**
     * Backwards-compatible single-argument form. Builds the opencode config
     * using the local provider with its default URL (subject to the
     * {@link #ENV_PROVIDER_URL} override) and no workspace secret lookup.
     *
     * @param request the agent-run request
     * @return a pretty-printed JSON document
     */
    String buildConfigJson(AgentRunRequest request) {
        String resolvedUrl = resolveProviderUrl(DEFAULT_PROVIDER_URL);
        return buildConfigJson(request, PROVIDER_ID, resolvedUrl, null, null, null);
    }

    /**
     * Builds the complete opencode config JSON for {@code request}.
     *
     * <p>The provider URL and secret details are supplied by the caller rather
     * than re-derived here. {@code OpencodeRunner.PROVIDER_MAP} is the single
     * source of truth for per-provider connection details; the runner resolves
     * those values and passes them in, ensuring this method never encodes a
     * parallel copy of the provider table.</p>
     *
     * @param request             the agent-run request
     * @param provider            provider identifier (local, openrouter, anthropic)
     * @param resolvedProviderUrl the URL to use as the OpenAI-compatible base URL,
     *                            already resolved by the caller (env override applied)
     * @param secretName          workspace secret name for this provider, or null
     * @param envVarName          provider-specific environment variable name, or null
     * @param secretLookup        optional workspace secret lookup function
     * @return a pretty-printed JSON document suitable for writing to a temp file
     */
    String buildConfigJson(AgentRunRequest request, String provider, String resolvedProviderUrl,
                           String secretName, String envVarName,
                           Function<String, String> secretLookup) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://opencode.ai/config.json");

        String effectiveProvider = (provider == null || provider.isEmpty()) ? PROVIDER_ID : provider;
        ObjectNode providers = root.putObject("provider");
        ObjectNode providerNode = providers.putObject(effectiveProvider);
        providerNode.put("npm", "@ai-sdk/openai-compatible");
        ObjectNode options = providerNode.putObject("options");

        options.put("baseURL", resolvedProviderUrl != null ? resolvedProviderUrl : DEFAULT_PROVIDER_URL);

        String key = resolveApiKey(secretName, envVarName, secretLookup);
        if (!key.isEmpty()) {
            options.put("apiKey", key);
        }

        ObjectNode models = providerNode.putObject("models");
        String modelName = resolveModel(request != null ? request.getModel() : null);
        models.putObject(modelName);
        root.put("model", effectiveProvider + "/" + modelName);

        ObjectNode mcp = translateMcpServers(request != null ? request.getMcpConfigJson() : null);
        if (mcp != null && mcp.size() > 0) {
            root.set("mcp", mcp);
        }

        ObjectNode permission = translateAllowlist(request != null ? request.getAllowedTools() : null);
        // opencode's --dangerously-skip-permissions flag does NOT cover the
        // external_directory permission (paths outside the session root), so
        // tool calls that touch files via an absolute path still hang on an
        // interactive prompt in headless mode. Granting it here makes the
        // permission policy explicit so subagents can read planning docs,
        // PR diffs, etc. without blocking.
        permission.put("external_directory", "allow");
        root.set("permission", permission);

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise opencode config", e);
        }
    }

    /**
     * Translates the orchestrator's canonical {@code {"mcpServers":{...}}} JSON
     * into opencode's {@code mcp} block.
     *
     * <p>opencode currently expects per-server entries of the shape
     * {@code {"type":"remote","url":...,"headers":{...},"enabled":true}} for
     * HTTP transports and {@code {"type":"local","command":[...],"enabled":true}}
     * for stdio servers. The exact field names are evolving; the translation
     * is isolated here so it can be adjusted in one place.</p>
     *
     * @param mcpConfigJson the orchestrator-built JSON (may be null/empty)
     * @return an object node ready to embed under the top-level {@code mcp} key;
     *         empty when no servers were declared
     */
    ObjectNode translateMcpServers(String mcpConfigJson) {
        ObjectNode out = MAPPER.createObjectNode();
        if (mcpConfigJson == null || mcpConfigJson.isEmpty()) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(mcpConfigJson);
        } catch (IOException e) {
            return out;
        }
        JsonNode servers = root.path("mcpServers");
        if (!servers.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = servers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode spec = entry.getValue();
            if (!spec.isObject()) continue;
            ObjectNode dst = out.putObject(name);
            String type = spec.path("type").asText("");
            if (type.equalsIgnoreCase("http") || spec.has("url")) {
                dst.put("type", "remote");
                dst.put("url", spec.path("url").asText(""));
                JsonNode headers = spec.get("headers");
                if (headers != null && headers.isObject()) {
                    dst.set("headers", headers.deepCopy());
                }
            } else {
                dst.put("type", "local");
                ArrayNode cmd = dst.putArray("command");
                String command = spec.path("command").asText("");
                if (!command.isEmpty()) {
                    cmd.add(command);
                }
                JsonNode args = spec.get("args");
                if (args != null && args.isArray()) {
                    for (JsonNode arg : args) {
                        cmd.add(arg.asText());
                    }
                }
                JsonNode env = spec.get("env");
                if (env != null && env.isObject()) {
                    dst.set("environment", env.deepCopy());
                }
            }
            dst.put("enabled", true);
        }
        return out;
    }

    /**
     * Translates the orchestrator's CSV allowlist into opencode's permission
     * block.
     *
     * <p>The result has two sections:</p>
     * <ul>
     *   <li>{@code tools}: built-in tool toggles for {@code Read}, {@code Edit},
     *       {@code Bash}, {@code Glob}, {@code Grep}, {@code Write}. Each entry
     *       is set to {@code "allow"} when the CSV lists it.</li>
     *   <li>{@code mcp}: per-server allow markers for every server that has at
     *       least one {@code mcp__server__tool} entry. Each value is the
     *       opencode {@code PermissionActionConfig} string {@code "allow"} —
     *       opencode 1.x does not support per-tool granularity on MCP servers,
     *       only a single action per server. Per-tool gating still happens via
     *       Claude Code's {@code --allowedTools} list; opencode's filtering
     *       happens at the registered-server level.</li>
     * </ul>
     *
     * <p>Unknown entries are ignored.</p>
     *
     * @param csv the orchestrator's CSV allowlist (may be null/empty)
     * @return the opencode permission block; empty when no tools were granted
     */
    ObjectNode translateAllowlist(String csv) {
        ObjectNode out = MAPPER.createObjectNode();
        if (csv == null || csv.isEmpty()) {
            return out;
        }
        List<String> entries = splitCsv(csv);
        ObjectNode tools = MAPPER.createObjectNode();
        Set<String> mcpServers = new LinkedHashSet<>();
        for (String entry : entries) {
            String value = entry.trim();
            if (value.isEmpty()) continue;
            if (BUILTIN_TOOLS.contains(value)) {
                tools.put(value, "allow");
                continue;
            }
            if (value.startsWith("mcp__")) {
                String[] parts = value.split("__", 3);
                if (parts.length >= 2 && !parts[1].isEmpty()) {
                    mcpServers.add(parts[1]);
                }
            }
        }
        if (tools.size() > 0) {
            out.set("tools", tools);
        }
        if (!mcpServers.isEmpty()) {
            ObjectNode mcp = out.putObject("mcp");
            for (String server : mcpServers) {
                mcp.put(server, "allow");
            }
        }
        return out;
    }

    /**
     * Splits {@code csv} into trimmed, non-empty entries.
     *
     * @param csv the comma-separated string
     * @return the list of entries (never {@code null})
     */
    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        for (String piece : csv.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
