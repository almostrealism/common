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

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Proves the two properties phase (a) of the pluggable-serialization work must
 * hold: under the default string format {@link JobFactory#toWire()} and
 * {@link Job#toWire()} are byte-identical to {@link JobFactory#encode()} /
 * {@link Job#encode()}, so no existing payload, peer or persisted blob changes;
 * and a node running the code from <em>before</em> phase (a) rejects a marked
 * payload rather than misparsing it.
 *
 * <p>The legacy-rejection cases run a copy of the pre-change decode loops
 * (kept here as fixtures) against a marked payload. The format marker's leading
 * {@code @wire=<id>} token is taken as a class name by that old code and fails
 * {@code Class.forName}, so the old decoders return {@code null} — nothing is
 * dispatched.</p>
 *
 * @author Michael Murray
 */
public class WireFormatCompatibilityTest extends TestSuiteBase {

    /** A marked payload a post-phase-(a) sender could emit for a coding job. */
    private static final String MARKED_FACTORY =
            "@wire=coding-pb1::io.flowtree.jobs.CodingAgentJob$Factory::QUJDRA==";

    /** A marked payload for a relayed coding job. */
    private static final String MARKED_JOB =
            "@wire=coding-pb1::io.flowtree.jobs.CodingAgentJob::QUJDRA==";

    /** A populated coding-agent factory encodes identically through encode() and toWire(). */
    @Test(timeout = 60000)
    public void codingFactoryToWireMatchesEncode() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt A", "Prompt B");
        factory.set("branch", "feature/x");
        assertStringWireIdentical(factory);
    }

    /** A bare coding-agent factory encodes identically through encode() and toWire(). */
    @Test(timeout = 60000)
    public void bareCodingAgentJobFactoryToWireMatchesEncode() {
        assertStringWireIdentical(new CodingAgentJobFactory());
    }

    /** A relayed coding job encodes identically through encode() and toWire(). */
    @Test(timeout = 60000)
    public void codingJobToWireMatchesEncode() {
        CodingAgentJob job = new CodingAgentJob();
        job.set("taskId", "t42");
        assertJobWireIdentical(job);
    }

    /** A shell-command job encodes identically through encode() and toWire(). */
    @Test(timeout = 60000)
    public void shellCommandJobToWireMatchesEncode() {
        assertJobWireIdentical(new ShellCommandJob("t1", "echo hi"));
    }

    /** An external-process factory encodes identically through encode() and toWire(). */
    @Test(timeout = 60000)
    public void externalProcessFactoryToWireMatchesEncode() {
        assertStringWireIdentical(new ExternalProcessJob.Factory());
    }

    /** The live decoder returns the same class and keeps the string format byte-identical end to end. */
    @Test(timeout = 60000)
    public void stringFactoryDecodesThroughLiveDecoder() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
        factory.set("branch", "b1");

        JobFactory decoded = JobFactory.decode(factory.toWire());

        Assert.assertNotNull(decoded);
        Assert.assertEquals(factory.getClass(), decoded.getClass());
        Assert.assertEquals(factory.encode(), decoded.encode());
    }

    /** A pre-phase-(a) factory decoder rejects a marked payload (returns null). */
    @Test(timeout = 60000)
    public void legacyFactoryDecoderRejectsMarkedPayload() {
        Assert.assertNull(legacyDecodeFactory(MARKED_FACTORY));
    }

    /** A pre-phase-(a) job decoder rejects a marked payload (returns null). */
    @Test(timeout = 60000)
    public void legacyJobDecoderRejectsMarkedPayload() {
        Assert.assertNull(legacyInstantiateJob(MARKED_JOB));
    }

    /** The live factory decoder also rejects a marked payload for an unregistered format. */
    @Test(timeout = 60000)
    public void liveFactoryDecoderRejectsUnregisteredFormat() {
        Assert.assertNull(JobFactory.decode(MARKED_FACTORY));
    }

    /**
     * Asserts {@link JobFactory#toWire()} equals {@link JobFactory#encode()} for the
     * default string format.
     *
     * @param factory the factory to check
     */
    private static void assertStringWireIdentical(JobFactory factory) {
        Assert.assertEquals(stringFormatId(), factory.getWireFormat());
        Assert.assertEquals(factory.encode(), factory.toWire());
    }

    /**
     * Asserts {@link Job#toWire()} equals {@link Job#encode()} for the default
     * string format.
     *
     * @param job the job to check
     */
    private static void assertJobWireIdentical(Job job) {
        Assert.assertEquals(stringFormatId(), job.getWireFormat());
        Assert.assertEquals(job.encode(), job.toWire());
    }

    /**
     * Returns the default string format id, read from the live codec so this
     * test needs no literal that could drift from {@code StringJobCodec}.
     *
     * @return the string format id
     */
    private static String stringFormatId() {
        return new CodingAgentJob().getWireFormat();
    }

    /**
     * A copy of the factory decode loop as it stood before phase (a), used to
     * prove an older node rejects a marked payload. It takes the first
     * {@link JobFactory#ENTRY_SEPARATOR}-delimited token as a class name and
     * instantiates it reflectively, returning {@code null} on any failure.
     *
     * @param data the encoded payload
     * @return the reconstructed factory, or {@code null} on failure
     */
    private static JobFactory legacyDecodeFactory(String data) {
        if (data == null) return null;
        int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
        if (index < 0) return null;
        String className = data.substring(0, index);
        try {
            return (JobFactory) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A copy of the job decode class resolution as it stood before phase (a).
     * It takes the first {@link JobFactory#ENTRY_SEPARATOR}-delimited token as a
     * class name and instantiates it reflectively, returning {@code null} on any
     * failure.
     *
     * @param data the encoded payload
     * @return the reconstructed job, or {@code null} on failure
     */
    private static Job legacyInstantiateJob(String data) {
        int index = data.indexOf(JobFactory.ENTRY_SEPARATOR);
        String className = data.substring(0, index);
        try {
            return (Job) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
}
