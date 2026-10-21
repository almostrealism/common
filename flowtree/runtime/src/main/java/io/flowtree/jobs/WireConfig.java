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

package io.flowtree.jobs;

import io.flowtree.job.JobFactory;

import java.util.function.BiConsumer;

/**
 * A cohesive group of {@link CodingAgentJob} configuration that the job and its
 * {@link CodingAgentJobFactory} share, and that travels to a worker in the
 * {@code ::key:=value} wire format.
 *
 * <p>The job and the factory each delegate to their own instance of a subclass,
 * so the two agree on defaults, validation and wire encoding by construction.
 * They differ only in how the state is serialized. A factory's configuration
 * travels as its property store, so the factory supplies a <em>property sink</em>
 * and every mutation publishes the wire keys it changed through
 * {@link #publish(String, String)}. A job supplies no sink; it is serialized by
 * calling {@link #appendEncoded(StringBuilder)}. The decode direction assigns
 * without publishing, so that reading a property back off the wire does not
 * re-emit it.</p>
 *
 * <p>A subclass describes its wire form once, in
 * {@link #writeWireProperties(BiConsumer)}; this class owns the
 * {@code ::key:=value} format that description is rendered into, and the
 * publication of the whole configuration through the sink.</p>
 *
 * @author Michael Murray
 * @see CodingAgentJobCodec
 */
abstract class WireConfig {

    /**
     * Receives {@code (key, value)} for each wire property a mutation changed,
     * or {@code null} when this configuration is not serialized as properties.
     */
    private final BiConsumer<String, String> propertySink;

    /**
     * Constructs a configuration that publishes its wire properties as they
     * change.
     *
     * @param propertySink receives {@code (key, value)} for each wire property
     *                     a mutation changed, or {@code null} for none
     */
    protected WireConfig(BiConsumer<String, String> propertySink) {
        this.propertySink = propertySink;
    }

    /**
     * Hands every wire property of this configuration to {@code sink}, with a
     * {@code null} value for each key that is omitted from the wire because it
     * is at its default.
     *
     * @param sink receives {@code (key, value)} for each wire property
     */
    protected abstract void writeWireProperties(BiConsumer<String, String> sink);

    /**
     * Appends this configuration in the {@code ::key:=value} wire format,
     * omitting the keys whose values are at their defaults.
     *
     * @param sb the buffer the owner's encoded form is being built in
     */
    final void appendEncoded(StringBuilder sb) {
        writeWireProperties((key, value) -> {
            if (value != null) {
                sb.append(JobFactory.ENTRY_SEPARATOR).append(key)
                        .append(JobFactory.KEY_VALUE_SEPARATOR).append(value);
            }
        });
    }

    /**
     * Forwards a changed wire property to the property sink, if any.
     *
     * @param key   the wire key
     * @param value the encoded value, or {@code null} to remove the key
     */
    protected void publish(String key, String value) {
        if (propertySink != null) {
            propertySink.accept(key, value);
        }
    }

    /**
     * Publishes every wire property of this configuration, removing from the
     * property sink each key that is at its default. Used by a mutation that
     * replaces the configuration wholesale.
     */
    protected void publishWireProperties() {
        writeWireProperties(this::publish);
    }

    /**
     * Decodes a positive {@code int} wire value; returns {@code fallback} when
     * the value is {@code null}, empty, unparseable, or not positive. Used by
     * capped-pass settings whose wire representations must round-trip through
     * legacy encodings that may contain absent or invalid integers.
     *
     * @param value    the wire-format value (may be {@code null} or empty)
     * @param fallback the default returned on parse failure or non-positive input
     * @return the parsed positive int, or {@code fallback}
     */
    static int parsePositiveOrDefault(String value, int fallback) {
        return parseAtLeastOrDefault(value, 1, fallback);
    }

    /**
     * Decodes an {@code int} wire value no smaller than {@code minimum}; returns
     * {@code fallback} when the value is {@code null}, empty, unparseable, or
     * below {@code minimum}. Used by settings for which zero is meaningful, such
     * as the enforce-changes re-run cap.
     *
     * @param value    the wire-format value (may be {@code null} or empty)
     * @param minimum  the smallest accepted value
     * @param fallback the default returned on parse failure or out-of-range input
     * @return the parsed int, or {@code fallback}
     */
    static int parseAtLeastOrDefault(String value, int minimum, int fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= minimum ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
