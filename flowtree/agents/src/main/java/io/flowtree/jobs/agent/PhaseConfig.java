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

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Per-phase configuration quadruple of {@code (runner, model, effort, provider)}
 * used by the unified per-phase precedence ladder.
 *
 * <p>Each field is independently nullable. A {@code null} value means
 * "this level has nothing to say about this field" — the resolver falls
 * through to the next precedence level for that field independently of
 * the other fields. See {@link PhaseConfigBundle} for the per-container
 * holder and {@code PhaseConfigResolver} for the seven-level ladder.</p>
 *
 * @param runner the {@link AgentRunner} identifier (e.g. {@code "claude"}),
 *               or {@code null} to inherit
 * @param model  the model identifier (e.g. {@code "claude-opus-4-7"}),
 *               or {@code null} to inherit
 * @param effort the effort level (e.g. {@code "high"}), or {@code null} to
 *               inherit
 * @param provider the provider identifier (e.g. {@code "openrouter"}),
 *                 or {@code null} to use the runner's default
 */
public record PhaseConfig(String runner, String model, String effort, String provider) {

    /** Sentinel: nothing configured at this level. */
    public static final PhaseConfig EMPTY = new PhaseConfig(null, null, null, null);

    /**
     * Convenience constructor for the legacy three-field form, prior to the
     * addition of the {@code provider} axis. The provider is set to {@code null},
     * which means the resolver will use the runner's default provider.
     *
     * @param runner the {@link AgentRunner} identifier, or {@code null} to inherit
     * @param model  the model identifier, or {@code null} to inherit
     * @param effort the effort level, or {@code null} to inherit
     */
    public PhaseConfig(String runner, String model, String effort) {
        this(runner, model, effort, null);
    }

    /**
     * Returns {@code true} when every field is {@code null}, meaning this
     * level contributes nothing to the resolved configuration.
     *
     * <p>{@code @JsonIgnore} keeps this derived predicate out of the YAML
     * / JSON wire form Jackson generates for {@link PhaseConfig}. Without
     * the annotation Jackson treats {@code isEmpty()} as a bean property
     * and writes an extra {@code empty: true|false} field that fails
     * deserialisation on reload (the record has no matching setter).</p>
     */
    @JsonIgnore
    public boolean isEmpty() {
        return runner == null && model == null && effort == null && provider == null;
    }

    /**
     * Returns a copy with the runner replaced; preserves model, effort, and provider.
     *
     * @param r the new runner identifier, may be {@code null}
     * @return a new {@link PhaseConfig}
     */
    public PhaseConfig withRunner(String r) {
        return new PhaseConfig(r, model, effort, provider);
    }

    /**
     * Returns a copy with the model replaced; preserves runner and effort.
     *
     * @param m the new model identifier, may be {@code null}
     * @return a new {@link PhaseConfig}
     */
    public PhaseConfig withModel(String m) {
        return new PhaseConfig(runner, m, effort, provider);
    }

    /**
     * Returns a copy with the effort replaced; preserves runner, model, and provider.
     *
     * @param e the new effort level, may be {@code null}
     * @return a new {@link PhaseConfig}
     */
    public PhaseConfig withEffort(String e) {
        return new PhaseConfig(runner, model, e, provider);
    }

    /**
     * Returns a copy with the provider replaced; preserves runner, model, and effort.
     *
     * @param p the new provider identifier, may be {@code null}
     * @return a new {@link PhaseConfig}
     */
    public PhaseConfig withProvider(String p) {
        return new PhaseConfig(runner, model, effort, p);
    }

    /**
     * Returns the provider/model attribution key for this configuration.
     *
     * <p>Returns {@code "provider/model"} when a provider is present
     * (e.g. {@code "openrouter/claude-opus-4-7"}) and just the model name
     * otherwise. A missing or empty model is reported as {@code "unknown"}
     * so cost is never silently dropped.</p>
     *
     * @return a stable key for per-model cost attribution
     */
    public String toModelKey() {
        String m = (model == null || model.isEmpty()) ? "unknown" : model;
        if (provider != null && !provider.isEmpty()) {
            return provider + "/" + m;
        }
        return m;
    }

    /**
     * Returns a configuration where each {@code null} field of {@code this}
     * is filled in from {@code other}; non-null fields of {@code this} win.
     * Used by the resolver to layer one precedence level on top of another.
     *
     * @param other the lower-precedence configuration to fall through to;
     *              {@code null} is treated as {@link #EMPTY}
     * @return the layered configuration
     */
    public PhaseConfig overlayOn(PhaseConfig other) {
        if (other == null) return this;
        return new PhaseConfig(
                runner != null ? runner : other.runner,
                model != null ? model : other.model,
                effort != null ? effort : other.effort,
                provider != null ? provider : other.provider);
    }

    /**
     * Like {@link #overlayOn(PhaseConfig)} but suppresses the base provider
     * when {@code this} sets a different runner and leaves its own provider
     * {@code null}. This prevents a provider value configured for one runner
     * (e.g. {@code "openrouter"} on {@code "opencode"}) from leaking into a
     * phase that resolves to a different runner (e.g. {@code "claude"}),
     * where the same provider would be incompatible.
     *
     * <p>If {@code this} explicitly sets a provider (non-null), that value
     * always wins — including incompatible combinations, which are caught by
     * {@code PhaseConfigResolver.validateProviderForRunner} after resolution.</p>
     *
     * @param other the lower-precedence configuration; {@code null} treated as
     *              {@link #EMPTY}
     * @return the layered configuration with runner-aware provider inheritance
     */
    public PhaseConfig overlayOnClearingInheritedProvider(PhaseConfig other) {
        if (other == null) return this;
        // Suppress other's provider when this changes the runner but sets no provider.
        String effectiveOtherProvider = (runner != null && provider == null
                && other.runner != null && !runner.equals(other.runner))
                ? null : other.provider;
        return new PhaseConfig(
                runner != null ? runner : other.runner,
                model != null ? model : other.model,
                effort != null ? effort : other.effort,
                provider != null ? provider : effectiveOtherProvider);
    }
}
