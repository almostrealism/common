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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link OpenRouterPricing} parses OpenRouter's
 * {@code /api/v1/models} response shape, resolves model ids tolerantly
 * (provider prefix and {@code :variant} suffix), and computes cost from token
 * counts using OpenRouter's per-token rates.
 */
public class OpenRouterPricingTest extends TestSuiteBase {

    /**
     * Representative slice of a real OpenRouter {@code /api/v1/models} payload:
     * a model with only prompt/completion rates (like {@code qwen/qwen3-coder}),
     * one that also publishes cache rates, and a free model.
     */
    private static final String CATALOG = "{\"data\":["
            + "{\"id\":\"qwen/qwen3-coder\",\"pricing\":"
            + "{\"prompt\":\"0.00000022\",\"completion\":\"0.0000018\"}},"
            + "{\"id\":\"x/cache-model\",\"pricing\":{\"prompt\":\"0.000001\","
            + "\"completion\":\"0.000002\",\"input_cache_read\":\"0.0000002\","
            + "\"input_cache_write\":\"0.0000005\"}},"
            + "{\"id\":\"x/free\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}}"
            + "]}";

    /** The three catalog entries are parsed. */
    @Test(timeout = 5000)
    public void parsesCatalog() {
        OpenRouterPricing pricing = OpenRouterPricing.parse(CATALOG);
        assertEquals(3, pricing.size());
    }

    /** An exact model id resolves to its rates. */
    @Test(timeout = 5000)
    public void resolvesExactId() {
        OpenRouterPricing.Rates rates = OpenRouterPricing.parse(CATALOG).ratesFor("qwen/qwen3-coder");
        assertEquals(0.00000022, rates.prompt(), 1e-15);
        assertEquals(0.0000018, rates.completion(), 1e-15);
    }

    /** A leading {@code openrouter/} provider prefix is stripped before lookup. */
    @Test(timeout = 5000)
    public void resolvesProviderPrefixedId() {
        OpenRouterPricing.Rates rates =
                OpenRouterPricing.parse(CATALOG).ratesFor("openrouter/qwen/qwen3-coder");
        assertEquals(0.00000022, rates.prompt(), 1e-15);
    }

    /** A {@code :variant} suffix (e.g. {@code :exacto}) falls back to the base model. */
    @Test(timeout = 5000)
    public void resolvesVariantSuffixToBaseModel() {
        OpenRouterPricing pricing = OpenRouterPricing.parse(CATALOG);
        assertEquals(0.0000018, pricing.ratesFor("qwen/qwen3-coder:exacto").completion(), 1e-15);
        assertEquals(0.0000018,
                pricing.ratesFor("openrouter/qwen/qwen3-coder:exacto").completion(), 1e-15);
    }

    /** Unknown models resolve to {@code null} so callers can fall back. */
    @Test(timeout = 5000)
    public void unknownModelResolvesToNull() {
        assertNull(OpenRouterPricing.parse(CATALOG).ratesFor("no/such-model"));
    }

    /**
     * Cost for a model without published cache rates: cached-input reads fall
     * back to the prompt rate. 1000 input + 100 output + 500 cache-read:
     * 1000*0.00000022 + 100*0.0000018 + 500*0.00000022 = 0.00051.
     */
    @Test(timeout = 5000)
    public void costFallsBackToPromptRateForCacheReadsWhenUnpriced() {
        OpenRouterPricing.Rates rates = OpenRouterPricing.parse(CATALOG).ratesFor("qwen/qwen3-coder");
        assertEquals(0.00051, rates.cost(1000, 100, 500, 0), 1e-12);
    }

    /**
     * Cost for a model with published cache rates uses them. 1000 input +
     * 100 output + 500 cache-read + 200 cache-write:
     * 1000*1e-6 + 100*2e-6 + 500*2e-7 + 200*5e-7 = 0.0014.
     */
    @Test(timeout = 5000)
    public void costUsesPublishedCacheRates() {
        OpenRouterPricing.Rates rates = OpenRouterPricing.parse(CATALOG).ratesFor("x/cache-model");
        assertEquals(0.0014, rates.cost(1000, 100, 500, 200), 1e-12);
    }

    /** A free model prices to exactly zero. */
    @Test(timeout = 5000)
    public void freeModelCostsZero() {
        OpenRouterPricing.Rates rates = OpenRouterPricing.parse(CATALOG).ratesFor("x/free");
        assertEquals(0.0, rates.cost(1000, 100, 500, 0), 1e-15);
    }

    /** Malformed or empty bodies yield an empty, non-throwing table. */
    @Test(timeout = 5000)
    public void tolerantOfBadInput() {
        assertEquals(0, OpenRouterPricing.parse("").size());
        assertEquals(0, OpenRouterPricing.parse("not json").size());
        assertEquals(0, OpenRouterPricing.parse("{\"data\":\"oops\"}").size());
        assertTrue(OpenRouterPricing.parse(null).size() == 0);
    }
}
