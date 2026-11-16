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

/**
 * The default {@link JobCodec}: the historic {@code classname::key:=value}
 * string format every FlowTree job type uses.
 *
 * <p>This codec is <em>never</em> marked on the wire (see {@link WireEnvelope}),
 * so {@link #encodePayload(JobFactory)} returns {@link JobFactory#encode()}
 * verbatim and every payload, persisted blob and peer written before the codec
 * abstraction existed stays byte-identical. It accepts every {@link JobFactory}
 * and {@link Job} class.</p>
 *
 * <p>The factory decode loop is the former body of {@link JobFactory#decode(String)}
 * and is strict: a class that cannot be instantiated, an entry without a
 * {@link JobFactory#KEY_VALUE_SEPARATOR}, or a {@link JobFactory#set(String, String)}
 * that throws yields {@code null}, never a partially-configured factory. The job
 * decode loop is the former body of the FlowTree job class loader and keeps its
 * historic lenient behaviour (see {@link #decodeJob(Class, String)}).</p>
 *
 * @author  Michael Murray
 * @see JobCodec
 * @see JobCodecRegistry
 */
public class StringJobCodec implements JobCodec {

    /** The format id of the default string codec. An unmarked payload is this format. */
    public static final String FORMAT_ID = "string";

    /**
     * Public no-argument constructor. Required because {@link JobCodecRegistry}
     * constructs the default codec directly and {@link java.util.ServiceLoader}
     * constructs registered codecs reflectively.
     */
    public StringJobCodec() { }

    @Override
    public String getFormatId() {
        return FORMAT_ID;
    }

    @Override
    public boolean supports(Class<?> type) {
        return JobFactory.class.isAssignableFrom(type) || Job.class.isAssignableFrom(type);
    }

    @Override
    public String encodePayload(JobFactory factory) {
        return factory.encode();
    }

    @Override
    public String encodePayload(Job job) {
        return job.encode();
    }

    /**
     * Reconstructs a {@link JobFactory} from the string payload, applying each
     * {@code key:=value} entry through {@link JobFactory#set(String, String)}.
     *
     * <p>Strict: returns {@code null} (never a partially-configured factory)
     * when the class cannot be instantiated, an entry lacks a
     * {@link JobFactory#KEY_VALUE_SEPARATOR}, or a {@code set} call throws. This
     * is the historic body of {@link JobFactory#decode(String)}, instantiating
     * the already-resolved {@code type} rather than re-resolving the class
     * name.</p>
     *
     * @param type    the factory class, resolved and verified by the caller
     * @param payload the full string payload, beginning with the class name
     * @return the reconstructed factory, or {@code null} on failure
     */
    @Override
    public JobFactory decodeFactory(Class<? extends JobFactory> type, String payload) {
        int index = payload.indexOf(JobFactory.ENTRY_SEPARATOR);
        if (index < 0) return null;

        JobFactory j;
        String data = payload;

        try {
            j = type.getDeclaredConstructor().newInstance();

            boolean end = false;

            while (!end) {
                data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
                index = JobFactory.nextEntrySeparator(data, data.indexOf(JobFactory.ENTRY_SEPARATOR));

                String s;

                if (index <= 0) {
                    s = data;
                    end = true;
                } else {
                    s = data.substring(0, index);
                }

                int k = s.indexOf(JobFactory.KEY_VALUE_SEPARATOR);
                String key = s.substring(0, k);
                String value = s.substring(k + JobFactory.KEY_VALUE_SEPARATOR.length());

                j.set(key, value);
            }
        } catch (Exception e) {
            // A factory missing the entries after the failure point (prompt,
            // branch, ...) must never be dispatched, so discard it entirely.
            Console.root().warn("StringJobCodec: failed to decode factory " + type.getName(), e);
            return null;
        }

        return j;
    }

    /**
     * Reconstructs a {@link Job} from the string payload, applying each
     * {@code key:=value} entry through {@link Job#set(String, String)}.
     *
     * <p>This is the historic body of the FlowTree job class loader and keeps
     * its two lenient behaviours, which differ from {@link #decodeFactory(Class, String)}:
     * a {@code set} that throws part-way leaves the instance partially
     * configured and that instance is still returned, and an entry without a
     * {@link JobFactory#KEY_VALUE_SEPARATOR} is taken as a key whose value is
     * the rest of the payload. Aligning the job decoder with the strict factory
     * decoder is tracked as an open question on the serialization plan and is
     * deliberately deferred here so this codec stays behaviour-identical to the
     * code it replaces; the only fixed behaviour is that a payload with no
     * {@link JobFactory#ENTRY_SEPARATOR} now yields {@code null} (the caller
     * rejects it before this method is reached) instead of throwing.</p>
     *
     * @param type    the job class, resolved and verified by the caller
     * @param payload the full string payload, beginning with the class name
     * @return the reconstructed job, or {@code null} when instantiation fails
     */
    @Override
    public Job decodeJob(Class<? extends Job> type, String payload) {
        int index = payload.indexOf(JobFactory.ENTRY_SEPARATOR);

        Job j = null;
        String data = payload;

        try {
            j = type.getDeclaredConstructor().newInstance();

            boolean end = false;

            while (!end) {
                data = data.substring(index + JobFactory.ENTRY_SEPARATOR.length());
                index = JobFactory.nextEntrySeparator(data, data.indexOf(JobFactory.ENTRY_SEPARATOR));

                String s;

                if (index <= 0) {
                    s = data;
                    end = true;
                } else {
                    s = data.substring(0, index);
                }

                int k = s.indexOf(JobFactory.KEY_VALUE_SEPARATOR);
                int len = JobFactory.KEY_VALUE_SEPARATOR.length();

                if (k > 0) {
                    String key = s.substring(0, k);
                    String value = s.substring(k + len);
                    j.set(key, value);
                } else {
                    String key = s;
                    String value = data.substring(index + len);
                    j.set(key, value);
                    end = true;
                }
            }
        } catch (Exception e) {
            Console.root().warn("Server: " + e);
        }

        return j;
    }
}
