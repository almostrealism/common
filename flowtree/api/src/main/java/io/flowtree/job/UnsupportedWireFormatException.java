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
 * Thrown when a wire payload names a serialization format this node cannot
 * decode, or when a {@link JobCodec} is asked to represent a type it does not
 * {@link JobCodec#supports(Class) support}.
 *
 * <p>A node in a mixed-version fleet may receive a payload whose format marker
 * (see {@link WireEnvelope}) names a format id that is not registered in its
 * {@link JobCodecRegistry}. Rather than guess at the bytes, the decode sites
 * raise this exception, log it with the offending format id, and refuse to
 * queue the job. A job is never run from a misparsed payload.</p>
 *
 * <p>On the sending side, {@link JobFactory#toWire()} and {@link Job#toWire()}
 * raise this exception when the codec selected by {@code getWireFormat()} does
 * not support the sending class, so a misconfigured format fails at the sender
 * rather than producing a payload no receiver can decode.</p>
 *
 * @author  Michael Murray
 * @see JobCodec
 * @see JobCodecRegistry
 * @see WireEnvelope
 */
public class UnsupportedWireFormatException extends RuntimeException {

    /**
     * Creates an exception with the given detail message.
     *
     * @param message a description of the unsupported format or type
     */
    public UnsupportedWireFormatException(String message) {
        super(message);
    }
}
