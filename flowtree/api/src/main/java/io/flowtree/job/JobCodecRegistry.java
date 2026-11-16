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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;
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
 * <p>A service-registered codec that cannot be loaded or registered (a broken
 * provider entry, a malformed or duplicate format id) is logged and skipped
 * while the registry is built, so one bad provider on the classpath never
 * prevents the string format, or any other codec, from working. The skipped
 * codec's format simply stays unsupported and is rejected like any other
 * unknown format.</p>
 *
 * @author  Michael Murray
 * @see JobCodec
 * @see WireEnvelope
 */
public final class JobCodecRegistry {

    /** The process-wide registry, built once with the string codec and every service-registered codec. */
    private static final JobCodecRegistry INSTANCE = new JobCodecRegistry(ServiceLoader.load(JobCodec.class));

    /** Codecs by format id, iteration-ordered so error messages list them predictably. */
    private final Map<String, JobCodec> codecs = new LinkedHashMap<>();

    /**
     * Builds a registry with the built-in {@link StringJobCodec} followed by the
     * given additional codecs. The process-wide registry passes the
     * {@link ServiceLoader} for {@link JobCodec}; a codec that fails to load or
     * to {@link #register(JobCodec) register} is logged and skipped, and the
     * remaining codecs are still registered.
     *
     * @param additional the codecs to register after the string codec
     */
    JobCodecRegistry(Iterable<JobCodec> additional) {
        register(new StringJobCodec());

        Iterator<JobCodec> providers = additional.iterator();
        while (true) {
            JobCodec codec;
            try {
                if (!providers.hasNext()) break;
                codec = providers.next();
            } catch (ServiceConfigurationError e) {
                // The provider iterator cannot be trusted to advance past a
                // broken entry, so stop loading rather than risk looping.
                Console.root().features(JobCodecRegistry.class)
                        .warn("stopped loading job codecs at a broken provider", e);
                break;
            }

            try {
                register(codec);
            } catch (RuntimeException e) {
                Console.root().features(JobCodecRegistry.class)
                        .warn("skipped job codec " + codec.getClass().getName(), e);
            }
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
     * <p>The format id must be {@link WireEnvelope#isValidFormatId(String) well formed},
     * since an id outside {@code [a-z0-9-]+} (one containing
     * {@link JobFactory#ENTRY_SEPARATOR}, for example) would render a marker that
     * does not parse back to the same format.</p>
     *
     * @param codec the codec to register
     * @throws IllegalArgumentException if the codec's format id is not well formed
     * @throws IllegalStateException    if the format id is already registered
     */
    public void register(JobCodec codec) {
        WireEnvelope.requireValidFormatId(codec.getFormatId());
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

    /**
     * Decodes a wire payload into an instance of {@code target}. This is the
     * single decode path behind {@link JobFactory#decode(String)} and
     * {@link Job#decode(String)}: it parses the {@link WireEnvelope} format
     * marker, selects the codec for that format, resolves the named class,
     * verifies that it is a {@code target} the codec
     * {@link JobCodec#supports(Class) supports}, and only then hands the payload
     * to {@code decoder}, so a rejected payload never instantiates anything.
     *
     * <p>Returns {@code null}, with a warning naming {@code target}, when
     * {@code data} is {@code null}, carries a structurally malformed marker,
     * carries no class name, names an unsupported wire format, names a class
     * that cannot be found or is not a {@code target}, names a class the codec
     * does not support, or when {@code decoder} throws. Whatever {@code decoder}
     * returns is otherwise returned unchanged; whether that can be a partially
     * configured instance is the codec's contract (see {@link JobCodec}).</p>
     *
     * @param <T>     the decoded type
     * @param target  the interface the named class must implement
     * @param data    the serialized wire payload, optionally preceded by a marker
     * @param decoder the codec operation that reconstructs the instance
     * @return the decoded instance, or {@code null} if it cannot be decoded
     */
    public <T> T decode(Class<T> target, String data, PayloadDecoder<T> decoder) {
        String label = target.getSimpleName() + ": ";
        if (data == null) return null;

        WireEnvelope envelope;
        JobCodec codec;
        try {
            envelope = WireEnvelope.parse(data);
            codec = forFormat(envelope.getFormatId());
        } catch (IllegalArgumentException | UnsupportedWireFormatException e) {
            Console.root().warn(label + e.getMessage(), e);
            return null;
        }

        String className = envelope.getClassName();
        if (className == null) {
            Console.root().warn(label + "payload has no class name: " + data);
            return null;
        }

        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException cnf) {
            Console.root().warn(label + "class not found: " + className, cnf);
            return null;
        }

        if (!target.isAssignableFrom(type)) {
            Console.root().warn(label + className + " is not a " + target.getSimpleName());
            return null;
        }

        if (!codec.supports(type)) {
            Console.root().warn(label + "codec '" + codec.getFormatId()
                    + "' does not support " + className);
            return null;
        }

        try {
            return decoder.decode(codec, type.asSubclass(target), envelope.getPayload());
        } catch (Exception e) {
            Console.root().warn(label + "failed to decode " + className, e);
            return null;
        }
    }

    /**
     * The codec operation {@link #decode(Class, String, PayloadDecoder)} applies
     * once the payload's class has been resolved and verified, typically
     * {@link JobCodec#decodeFactory(Class, String)} or
     * {@link JobCodec#decodeJob(Class, String)}.
     *
     * @param <T> the decoded type
     */
    @FunctionalInterface
    public interface PayloadDecoder<T> {
        /**
         * Reconstructs an instance of the resolved class from the payload body.
         *
         * @param codec   the codec selected by the payload's format id
         * @param type    the resolved and verified class to instantiate
         * @param payload the payload body from {@link WireEnvelope#getPayload()}
         * @return the reconstructed instance, or {@code null} on a decode failure
         */
        T decode(JobCodec codec, Class<? extends T> type, String payload);
    }
}
