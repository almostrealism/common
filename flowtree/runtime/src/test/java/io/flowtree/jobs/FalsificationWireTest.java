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

import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end wire coverage for the {@code falsificationEnabled} opt-in flag, and
 * the MCP-parameter registration that exposes it to operators.
 *
 * <p>The flag follows the exact path that silently dropped {@code dispatchCapable}
 * on the wire (a plain field the codec never serialised), so these tests assert
 * against the real artifacts: the encoded wire strings, the rebuilt
 * factory/job, the live MCP tool signature, and — the part a boolean assertion
 * cannot prove — that a job carrying the wire-restored flag actually REACHES
 * {@link FalsificationPhase} in {@code doWork()}.</p>
 *
 * <p>Editing the base-branch {@code CodingAgentJobRetrospectiveTest} /
 * {@code McpToolDiscoveryTest} would trip the agent write-lock, so the new cases
 * live in this sibling class.</p>
 *
 * @see Phase#FALSIFICATION
 * @see CodingAgentJobCodec
 */
public class FalsificationWireTest extends TestSuiteBase {

    // ── Phase wire mapping ──────────────────────────────────────────────────

    /** Phase.fromWireName("falsification") resolves to Phase.FALSIFICATION. */
    @Test(timeout = 30000)
    public void phaseFalsificationExists() {
        assertEquals(Phase.FALSIFICATION, Phase.fromWireName("falsification"));
        assertEquals(Phase.FALSIFICATION, Phase.fromRuleName("falsification"));
        assertEquals("falsification", Phase.FALSIFICATION.wireName());
    }

    // ── Job flag + wire round-trip ──────────────────────────────────────────

    /** CodingAgentJob falsificationEnabled defaults to false. */
    @Test(timeout = 30000)
    public void jobFalsificationDefaultsFalse() {
        assertFalse(new CodingAgentJob("t1", "p").isFalsificationEnabled());
    }

    /** setFalsificationEnabled is readable back, both directions. */
    @Test(timeout = 30000)
    public void jobSetFalsificationReadableBack() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setFalsificationEnabled(true);
        assertTrue(job.isFalsificationEnabled());
        job.setFalsificationEnabled(false);
        assertFalse(job.isFalsificationEnabled());
    }

    /** The flag is absent from the wire format at its default (false), keeping the wire minimal. */
    @Test(timeout = 30000)
    public void falsificationAbsentFromWireWhenFalse() {
        String encoded = new CodingAgentJob("t1", "p").encode();
        assertFalse("falsificationEnabled must not appear when default (false): " + encoded,
                encoded.contains("falsificationEnabled"));
    }

    /** The flag appears in the wire format when set true. */
    @Test(timeout = 30000)
    public void falsificationAppearsInWireWhenTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "p");
        job.setFalsificationEnabled(true);
        String encoded = job.encode();
        assertTrue("Expected falsificationEnabled:=true in: " + encoded,
                encoded.contains("falsificationEnabled:=true"));
    }

    /** job.set("falsificationEnabled","true") routes through the codec applySetting branch. */
    @Test(timeout = 30000)
    public void jobSetDecodesFalsificationKey() {
        CodingAgentJob job = new CodingAgentJob();
        job.set("falsificationEnabled", "true");
        assertTrue(job.isFalsificationEnabled());
    }

    /** The job flag survives a full encode/decode round-trip — the dispatchCapable regression class. */
    @Test(timeout = 30000)
    public void jobFalsificationSurvivesWireRoundTrip() {
        CodingAgentJob original = new CodingAgentJob("t1", "p");
        original.setFalsificationEnabled(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertTrue("falsificationEnabled=true must survive the job wire round-trip",
                restored.isFalsificationEnabled());
    }

    /** A non-falsification job round-trips to non-falsification. */
    @Test(timeout = 30000)
    public void jobNonFalsificationRoundTripsFalse() {
        CodingAgentJob restored =
                GitManagedJobSerializationTest.roundTrip(new CodingAgentJob("t1", "p"));
        assertFalse(restored.isFalsificationEnabled());
    }

    // ── Factory flag + wire round-trip + propagation ────────────────────────

    /** Factory falsificationEnabled defaults to false. */
    @Test(timeout = 30000)
    public void factoryFalsificationDefaultsFalse() {
        assertFalse(new CodingAgentJobFactory("p").isFalsificationEnabled());
    }

    /** Factory flag survives the factory encode/decode round-trip (the remote-dispatch path). */
    @Test(timeout = 30000)
    public void factoryFalsificationSurvivesWireRoundTrip() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory();
        original.setFalsificationEnabled(true);
        String encoded = original.encode();
        assertTrue("encoded factory must carry the falsificationEnabled property: " + encoded,
                encoded.contains("falsificationEnabled"));
        CodingAgentJob.Factory restored =
                GitManagedJobSerializationTest.roundTripFactory(original);
        assertTrue("falsificationEnabled=true must survive the factory wire round-trip",
                restored.isFalsificationEnabled());
    }

    /** Factory propagates the flag onto the job it builds. */
    @Test(timeout = 30000)
    public void factoryPropagatesFalsificationToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.setFalsificationEnabled(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue(job.isFalsificationEnabled());
    }

    /** A wire-restored factory still builds a falsification-enabled job (composes both fixes). */
    @Test(timeout = 30000)
    public void wireRoundTrippedFactoryStillBuildsFalsificationJob() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory();
        original.setPrompts("do work");
        original.setFalsificationEnabled(true);
        CodingAgentJob.Factory restored =
                GitManagedJobSerializationTest.roundTripFactory(original);
        assertTrue("A wire-restored falsification factory must still build a falsification job",
                ((CodingAgentJob) restored.nextJob()).isFalsificationEnabled());
    }

    // ── The real artifact: wire-survival composed with reaching the phase ───

    /**
     * The end-to-end guarantee a boolean assertion cannot give: the flag
     * survives the wire round-trip AND a job carrying the restored flag actually
     * REACHES {@link FalsificationPhase} in {@code doWork()}. This pins the exact
     * failure mode that dropped {@code dispatchCapable} — where every layer's
     * boolean was set yet the effect never fired.
     */
    @Test(timeout = 30000)
    public void enabledFlagSurvivesWireAndReachesPhase() {
        CodingAgentJob original = new CodingAgentJob("t-wire-reach", "p");
        original.setFalsificationEnabled(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertTrue("Pre-condition: the flag must survive the wire", restored.isFalsificationEnabled());

        FalsificationPhaseTest.SpyCodingAgentJob spy =
                new FalsificationPhaseTest.SpyCodingAgentJob("t-wire-reach", "p");
        spy.setFalsificationEnabled(restored.isFalsificationEnabled());
        spy.doWork();
        assertTrue("A job carrying the wire-restored flag must REACH the falsification phase",
                spy.falsificationPhaseCalled);
    }

    // ── MCP parameter registration (do not make the registration gap recur) ─

    /**
     * The {@code falsification_enabled} parameter must be declared in the live
     * {@code workstream_submit_task} signature in {@code server.py}. The
     * registration gap (a controller field forwarded in the payload but never
     * declared as an MCP parameter, leaving the feature unreachable) has
     * recurred repeatedly; this is the tripwire for the falsification flag.
     */
    @Test(timeout = 30000)
    public void submitTaskDeclaresFalsificationEnabledParam() {
        Path serverFile = McpToolDiscovery.locateManagerServerPy();
        assertNotNull("Could not locate tools/mcp/manager/server.py from working directory "
                + Path.of("").toAbsolutePath()
                + "; the falsification MCP-param check cannot run without it.", serverFile);

        List<String> submitParams =
                McpToolDiscovery.discoverToolParameters(serverFile, "workstream_submit_task");
        assertTrue("workstream_submit_task must declare the falsification_enabled parameter so"
                + " operators can opt a job into the falsification phase via MCP. Declared params: "
                + submitParams, submitParams.contains("falsification_enabled"));
    }
}
