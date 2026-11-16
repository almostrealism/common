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

package io.flowtree.job;

import org.almostrealism.io.Console;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Maps a wire format id to the {@link JobCodec} that decodes it.
 *
 * <p>The registry always contains the {@link StringJobCodec}. Further codecs
 * register through {@link ServiceLoader} — a {@code META-INF/services/io.flowtree.job.JobCodec}
 * resource on the classpath — so {@code flowtree/api} never depends on any
 * serialization library and a module that adds a format (for example protobuf
 * in {@code flowtree/runtime}) contributes its codec without the api module
 * knowing about it.</p>
 *
 * <p>A payload whose {@link WireEnvelope#getFormatId() format id} is not
 * registered raises {@link UnsupportedWireFormatException} from
 * {@link #forFormat(String)}, so a node in a mixed-version fleet rejects a
 * format it does not understand rather than misparsing it.</p>
 *
 * @author  Michael Murray
 * @see JobCodec
 * @see WireEnvelope
 */
public final class JobCodecRegistry {

    /** The process-wide registry, built once with the string codec and every service-registered codec. */
    private static final JobCodecRegistry INSTANCE = new JobCodecRegistry();

    /** Codecs by format id, iteration-ordered so error messages list them predictably. */
    private final Map<String, JobCodec> codecs = new LinkedHashMap<>();

    /** Builds the registry with the built-in {@link StringJobCodec} and every {@link ServiceLoader}-registered codec. */
    private JobCodecRegistry() {
        register(new StringJobCodec());
        for (JobCodec codec : ServiceLoader.load(JobCodec.class)) {
            register(codec);
        }
    }

    /**
     * Returns the process-wide registry.
     *
     * @return the singleton registry
     */
    public static JobCodecRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a codec under its {@link JobCodec#getFormatId() format id}. A
     * codec whose format id is already registered is rejected, so a duplicate
     * service registration cannot silently shadow the built-in string codec.
     *
     * @param codec the codec to register
     * @throws IllegalStateException if the format id is already registered
     */
    public void register(JobCodec codec) {
        JobCodec existing = codecs.putIfAbsent(codec.getFormatId(), codec);
        if (existing != null && existing != codec) {
            throw new IllegalStateException("duplicate job codec for format '"
                    + codec.getFormatId() + "': " + existing.getClass().getName()
                    + " and " + codec.getClass().getName());
        }
        Console.root().features(JobCodecRegistry.class)
                .log("registered job codec '" + codec.getFormatId() + "'");
    }

    /**
     * Returns the codec for the given format id.
     *
     * @param formatId the format id from a {@link WireEnvelope}
     * @return the codec registered for that format
     * @throws UnsupportedWireFormatException if no codec is registered for the id
     */
    public JobCodec forFormat(String formatId) {
        JobCodec codec = codecs.get(formatId);
        if (codec == null) {
            throw new UnsupportedWireFormatException("wire format '" + formatId
                    + "' is not supported by this node; supported: " + codecs.keySet());
        }
        return codec;
    }
}
