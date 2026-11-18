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

/**
 * A pluggable serialization format for {@link JobFactory} and {@link Job}
 * instances travelling across the FlowTree node cluster.
 *
 * <p>Every codec is identified by a {@link #getFormatId() format id} that a
 * receiver reads from the payload's marker (see {@link WireEnvelope}) to choose
 * the codec that decodes it. The {@link StringJobCodec} carries the default
 * {@code classname::key:=value} format and is always registered; further codecs
 * register through {@link java.util.ServiceLoader} so {@code flowtree/api}
 * depends on no serialization library.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>A codec that cannot represent a given type throws
 *       {@link UnsupportedWireFormatException}. The decode sites consult
 *       {@link #supports(Class)} <em>before</em> instantiating anything, so a
 *       mismatched payload never constructs an object.</li>
 *   <li>{@link #decodeFactory(Class, String)} never returns a partially
 *       configured factory: a payload that fails to apply in full yields
 *       {@code null} or an exception, never an object missing part of its
 *       definition. {@link #decodeJob(Class, String)} holds to the same
 *       contract, except where an implementation documents a legacy exception
 *       (see {@link StringJobCodec#decodeJob(Class, String)}).</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see JobCodecRegistry
 * @see WireEnvelope
 * @see StringJobCodec
 */
public interface JobCodec {

    /**
     * Returns the stable format id for this codec, matching {@code [a-z0-9-]+}.
     * It appears in a marked payload's {@link WireEnvelope} and selects this
     * codec from the {@link JobCodecRegistry}.
     *
     * @return the format id
     */
    String getFormatId();

    /**
     * Returns whether this codec can represent instances of the given class.
     * The decode sites call this after resolving the payload's named class and
     * before instantiating it, so a payload whose class the codec does not
     * accept is rejected rather than decoded with the wrong codec.
     *
     * @param type the resolved {@link JobFactory} or {@link Job} class
     * @return {@code true} if this codec accepts the class; {@code false} otherwise
     */
    boolean supports(Class<?> type);

    /**
     * Encodes a factory into this codec's payload body — the text that
     * {@link WireEnvelope#getPayload()} carries.
     *
     * @param factory the factory to encode
     * @return the codec-specific payload body
     * @throws UnsupportedWireFormatException if this codec cannot represent the factory
     */
    String encodePayload(JobFactory factory);

    /**
     * Encodes a job into this codec's payload body — the text that
     * {@link WireEnvelope#getPayload()} carries.
     *
     * @param job the job to encode
     * @return the codec-specific payload body
     * @throws UnsupportedWireFormatException if this codec cannot represent the job
     */
    String encodePayload(Job job);

    /**
     * Reconstructs a factory of the given, already-resolved class from this
     * codec's payload body.
     *
     * @param type    the factory class to instantiate, resolved and verified by the caller
     * @param payload the payload body from {@link WireEnvelope#getPayload()}
     * @return the reconstructed factory, or {@code null} on a decode failure
     * @throws UnsupportedWireFormatException if this codec cannot represent the type
     */
    JobFactory decodeFactory(Class<? extends JobFactory> type, String payload);

    /**
     * Reconstructs a job of the given, already-resolved class from this codec's
     * payload body.
     *
     * @param type    the job class to instantiate, resolved and verified by the caller
     * @param payload the payload body from {@link WireEnvelope#getPayload()}
     * @return the reconstructed job, or {@code null} on a decode failure
     * @throws UnsupportedWireFormatException if this codec cannot represent the type
     */
    Job decodeJob(Class<? extends Job> type, String payload);

    /**
     * Wraps an already-encoded payload as the serialized wire form for a class
     * this codec produced it from. This is the shared body of
     * {@link JobFactory#toWire()} and {@link Job#toWire()}: it verifies this
     * codec {@link #supports(Class) supports} the class, returns the payload
     * unchanged for the byte-identical string format, and otherwise renders a
     * marked {@link WireEnvelope} carrying the format id, class name and payload.
     *
     * @param type    the class the payload was encoded from
     * @param payload the payload body from {@link #encodePayload(JobFactory)} or
     *                {@link #encodePayload(Job)}
     * @return the serialized wire payload
     * @throws UnsupportedWireFormatException if this codec does not support the class
     */
    default String renderWire(Class<?> type, String payload) {
        if (!supports(type)) {
            throw new UnsupportedWireFormatException("codec '" + getFormatId()
                    + "' does not support " + type.getName());
        }
        if (StringJobCodec.FORMAT_ID.equals(getFormatId())) {
            return payload;
        }
        return new WireEnvelope(getFormatId(), type.getName(), payload).render();
    }
}
