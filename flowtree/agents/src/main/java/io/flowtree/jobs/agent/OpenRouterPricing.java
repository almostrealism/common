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

import static io.flowtree.JsonFieldExtractor.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenRouter's published per-token pricing, used to recover the dollar cost of
 * an opencode session from the per-step token counts opencode reports.
 *
 * <p>opencode prices a session by multiplying token counts by the rates in its
 * bundled models.dev catalog (or an {@code opencode.json} {@code cost} block).
 * It does <em>not</em> use the cost OpenRouter returns on each response, and the
 * config-{@code cost} path is a no-op for the {@code @ai-sdk/openai-compatible}
 * provider FlowTree configures (opencode issue #24113). As a result, any model
 * id absent from the catalog — notably OpenRouter <em>variant</em> ids such as
 * {@code qwen/qwen3-coder:exacto} — is reported at {@code cost = 0} on every
 * step even though OpenRouter billed for it.</p>
 *
 * <p>opencode does report accurate per-step token counts regardless, so this
 * class recovers the dollar figure by multiplying those tokens by OpenRouter's
 * live rates from {@code GET }{@value #MODELS_URL}. The result is grounded in
 * OpenRouter's published per-token prices rather than opencode's catalog
 * snapshot, and works for every model and variant.</p>
 */
final class OpenRouterPricing {

    /** OpenRouter's public models/pricing endpoint (no authentication required). */
    static final String MODELS_URL = "https://openrouter.ai/api/v1/models";

    /** How long a successful catalog fetch is reused before being refreshed. */
    private static final Duration TTL = Duration.ofHours(6);

    /** Connect/read timeout for the catalog fetch. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    /** Cached instance, refreshed when older than {@link #TTL}. */
    private static volatile OpenRouterPricing cached;
    /** Wall-clock time the cached instance was fetched. */
    private static volatile Instant cachedAt;

    /** Per-token rates keyed by OpenRouter model id (e.g. {@code "qwen/qwen3-coder"}). */
    private final Map<String, Rates> rates;

    /**
     * Per-token USD rates for one model. Each value is dollars per single token
     * (OpenRouter publishes per-token, not per-million).
     *
     * @param prompt     rate for fresh (uncached) input tokens
     * @param completion rate for output tokens
     * @param cacheRead  rate for cached-input reads; {@code 0} when OpenRouter
     *                   publishes no separate cache-read rate
     * @param cacheWrite rate for cache writes; {@code 0} when not published
     */
    record Rates(double prompt, double completion, double cacheRead, double cacheWrite) {

        /**
         * Computes the USD cost of a set of token counts. Cached-input reads are
         * billed at {@link #cacheRead} when OpenRouter publishes that rate, and
         * otherwise fall back to the full {@link #prompt} rate (opencode reports
         * cache reads separately from fresh input, so they would otherwise be
         * unpriced).
         *
         * @param inputTokens      fresh (uncached) input tokens
         * @param outputTokens     output tokens
         * @param cacheReadTokens  cached-input read tokens
         * @param cacheWriteTokens cache-write tokens
         * @return the total cost in USD
         */
        double cost(long inputTokens, long outputTokens,
                    long cacheReadTokens, long cacheWriteTokens) {
            double cacheReadRate = cacheRead > 0.0 ? cacheRead : prompt;
            return inputTokens * prompt
                    + outputTokens * completion
                    + cacheReadTokens * cacheReadRate
                    + cacheWriteTokens * cacheWrite;
        }
    }

    /**
     * Constructs a pricing table from an already-parsed model→rates map.
     * Exposed for tests so cost math can be verified without network access.
     *
     * @param rates model id to {@link Rates}; never {@code null}
     */
    OpenRouterPricing(Map<String, Rates> rates) {
        this.rates = rates == null ? Map.of() : Map.copyOf(rates);
    }

    /**
     * Returns the rates for {@code model}, tolerating FlowTree's qualified
     * {@code provider/model} form and OpenRouter {@code :variant} suffixes.
     * Lookups are tried most-specific first: the id as given, then with any
     * leading {@code openrouter/} provider prefix removed, then with any
     * trailing {@code :variant} suffix removed (so {@code qwen/qwen3-coder:exacto}
     * resolves to the base {@code qwen/qwen3-coder} entry).
     *
     * @param model the model id (may include a provider prefix and/or variant)
     * @return the matching {@link Rates}, or {@code null} when unknown
     */
    Rates ratesFor(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }
        Rates direct = rates.get(model);
        if (direct != null) {
            return direct;
        }
        String id = model;
        if (id.startsWith("openrouter/")) {
            id = id.substring("openrouter/".length());
            Rates withoutProvider = rates.get(id);
            if (withoutProvider != null) {
                return withoutProvider;
            }
        }
        int colon = id.indexOf(':');
        if (colon > 0) {
            return rates.get(id.substring(0, colon));
        }
        return null;
    }

    /**
     * Returns the number of priced models in this table. Exposed for tests.
     *
     * @return the model count
     */
    int size() {
        return rates.size();
    }

    /**
     * Returns the shared pricing table, fetching OpenRouter's catalog on first
     * use and refreshing it once older than {@link #TTL}. On any fetch failure
     * an empty table is returned (so callers fall back to opencode's own cost
     * rather than failing the run); the failure is not cached, so the next call
     * retries.
     *
     * @return a pricing table; possibly empty when the catalog is unreachable
     */
    static OpenRouterPricing cached() {
        OpenRouterPricing local = cached;
        Instant at = cachedAt;
        if (local != null && at != null
                && Duration.between(at, Instant.now()).compareTo(TTL) < 0) {
            return local;
        }
        OpenRouterPricing fetched = fetch();
        if (fetched.size() > 0) {
            cached = fetched;
            cachedAt = Instant.now();
        }
        return fetched;
    }

    /**
     * Fetches and parses OpenRouter's model catalog. Never throws; returns an
     * empty table on any error.
     *
     * @return the parsed pricing table, or an empty one on failure
     */
    private static OpenRouterPricing fetch() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(FETCH_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(MODELS_URL))
                    .GET()
                    .timeout(FETCH_TIMEOUT)
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new OpenRouterPricing(Map.of());
            }
            return parse(response.body());
        } catch (RuntimeException | IOException e) {
            return new OpenRouterPricing(Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OpenRouterPricing(Map.of());
        }
    }

    /**
     * Parses an OpenRouter {@code /api/v1/models} response body into a pricing
     * table. The response is {@code {"data":[{"id":...,"pricing":{"prompt":...,
     * "completion":...,"input_cache_read":...,"input_cache_write":...}}, ...]}}
     * with rates encoded as decimal strings in USD per token. Models without a
     * parseable {@code prompt} rate are skipped.
     *
     * @param body the raw JSON response body
     * @return the parsed pricing table (never {@code null}; possibly empty)
     */
    static OpenRouterPricing parse(String body) {
        Map<String, Rates> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return new OpenRouterPricing(out);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return new OpenRouterPricing(out);
            }
            for (JsonNode model : data) {
                JsonNode idNode = model.get("id");
                JsonNode pricing = model.get("pricing");
                if (idNode == null || !idNode.isTextual()
                        || pricing == null || !pricing.isObject()) {
                    continue;
                }
                double prompt = rate(pricing, "prompt");
                double completion = rate(pricing, "completion");
                double cacheRead = rate(pricing, "input_cache_read");
                double cacheWrite = rate(pricing, "input_cache_write");
                if (prompt <= 0.0 && completion <= 0.0) {
                    // No usable rate (free model or unpriced); keep it so the
                    // lookup resolves to an explicit zero cost rather than null.
                    out.put(idNode.asText(), new Rates(0.0, 0.0, 0.0, 0.0));
                    continue;
                }
                out.put(idNode.asText(),
                        new Rates(prompt, completion, cacheRead, cacheWrite));
            }
        } catch (IOException e) {
            return new OpenRouterPricing(out);
        }
        return new OpenRouterPricing(out);
    }

    /**
     * Reads a decimal-string rate field, returning {@code 0} when absent or
     * unparseable. OpenRouter encodes rates as strings (e.g. {@code "0.00000022"}).
     *
     * @param pricing the pricing object
     * @param field   the field name
     * @return the parsed rate, or {@code 0}
     */
    private static double rate(JsonNode pricing, String field) {
        JsonNode node = pricing.get(field);
        if (node == null || node.isNull()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
