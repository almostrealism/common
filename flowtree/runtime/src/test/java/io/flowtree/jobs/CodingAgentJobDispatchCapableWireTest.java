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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end coverage for the {@code dispatchCapable} flag along the
 * Claude-Code harness path: the workstream config sets the flag, the
 * {@link CodingAgentJobFactory} carries it across the wire to a remote
 * agent node, the {@link CodingAgentJob} it builds carries it across its
 * own wire round-trip, and {@link McpConfigBuilder#buildAllowedTools(String)}
 * appends the dispatch tools to the GENERATED allowed-tools CSV the
 * launched agent actually receives.
 *
 * <p>These tests assert against the real artifacts — the encoded wire
 * strings and the composed CSV — not against a boolean field. The
 * dispatch feature was repeatedly shipped broken because each layer was
 * tested in isolation: the controller field was set, the MCP parameter
 * was wired, the CSV append was added — yet the flag was silently
 * dropped on the {@link CodingAgentJobFactory} encode/decode round-trip
 * (it was a plain field, not a {@code properties}-map entry), so a job
 * dispatched to a remote agent node lost the grant and the Claude-Code
 * agent was denied {@code workstream_register}. The
 * {@link #factoryDispatchCapableSurvivesWireRoundTrip()} and
 * {@link #wireRoundTrippedJobStillComposesCsvWithDispatchTools()} tests
 * pin that exact regression.</p>
 *
 * <p>This is a new file: {@link CodingAgentJobDispatchTest} and
 * {@link McpConfigBuilderDispatchCapableTest} on the base branch are
 * covered by the agent write-lock, so the new cases live in a sibling
 * class.</p>
 *
 * @see McpConfigBuilder#buildAllowedTools(String)
 * @see CodingAgentJobCodec
 */
public class CodingAgentJobDispatchCapableWireTest extends TestSuiteBase {

    /** A non-empty ar-manager URL so the allowlist build emits ar-manager entries. */
    private static final String AR_MANAGER_URL = "http://ar-manager:8010";

    /** A non-empty ar-manager token so the allowlist build emits ar-manager entries. */
    private static final String AR_MANAGER_TOKEN = "armt_tmp_dispatchtest";

    /** The {@code mcp__ar-manager__} prefix used by the allowed-tools CSV. */
    private static final String PREFIX = "mcp__ar-manager__";

    // ------------------------------------------------------------------
    // Factory: the dispatchCapable flag lives in the properties map so it
    // survives the encode/decode wire round-trip used to ship a factory to
    // a remote agent node. This is the layer the regression was hidden in.
    // ------------------------------------------------------------------

    /** A fresh factory is not dispatch-capable by default. */
    @Test(timeout = 10000)
    public void factoryDispatchCapableDefaultsFalse() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory();
        assertFalse("A factory must default to dispatchCapable=false",
                factory.isDispatchCapable());
    }

    /** {@code setDispatchCapable(true)} is readable back through the getter. */
    @Test(timeout = 10000)
    public void factorySetDispatchCapableIsReadableBack() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory();
        factory.setDispatchCapable(true);
        assertTrue("setDispatchCapable(true) must be visible via isDispatchCapable()",
                factory.isDispatchCapable());
        factory.setDispatchCapable(false);
        assertFalse("setDispatchCapable(false) must clear the flag",
                factory.isDispatchCapable());
    }

    /**
     * The regression test for the wire-drop bug: a dispatch-capable
     * factory must still be dispatch-capable after an encode/decode
     * round-trip. Before the fix, {@code setDispatchCapable} wrote a
     * plain field that {@link io.flowtree.job.AbstractJobFactory#encode()}
     * never serialized, so the reconstructed factory was non-dispatch.
     */
    @Test(timeout = 10000)
    public void factoryDispatchCapableSurvivesWireRoundTrip() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory();
        original.setDispatchCapable(true);
        String encoded = original.encode();
        assertTrue("encoded factory must carry the dispatchCapable property: " + encoded,
                encoded.contains("dispatchCapable"));

        CodingAgentJob.Factory restored =
                GitManagedJobSerializationTest.roundTripFactory(original);
        assertTrue("dispatchCapable=true must survive the factory wire round-trip"
                + " (the remote-agent-node dispatch path)",
                restored.isDispatchCapable());
    }

    /** A non-dispatch factory stays non-dispatch across the wire round-trip. */
    @Test(timeout = 10000)
    public void factoryNonDispatchSurvivesWireRoundTrip() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory();
        // dispatchCapable left at its default false.
        CodingAgentJob.Factory restored =
                GitManagedJobSerializationTest.roundTripFactory(original);
        assertFalse("A non-dispatch factory must remain non-dispatch after a"
                + " wire round-trip", restored.isDispatchCapable());
    }

    // ------------------------------------------------------------------
    // Factory -> Job propagation: nextJob() copies the flag onto the job.
    // ------------------------------------------------------------------

    /** {@code nextJob()} propagates dispatchCapable=true onto the built job. */
    @Test(timeout = 10000)
    public void factoryNextJobPropagatesDispatchCapableTrue() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory();
        factory.setPrompts("do orchestration work");
        factory.setDispatchCapable(true);
        Job next = factory.nextJob();
        assertTrue("nextJob() must yield a CodingAgentJob",
                next instanceof CodingAgentJob);
        assertTrue("nextJob() must propagate dispatchCapable=true to the job",
                ((CodingAgentJob) next).isDispatchCapable());
    }

    /** {@code nextJob()} leaves dispatchCapable=false when the factory is non-dispatch. */
    @Test(timeout = 10000)
    public void factoryNextJobPropagatesDispatchCapableFalse() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory();
        factory.setPrompts("do ordinary work");
        Job next = factory.nextJob();
        assertFalse("nextJob() must leave dispatchCapable=false for a"
                + " non-dispatch factory", ((CodingAgentJob) next).isDispatchCapable());
    }

    /**
     * The whole-path propagation: a factory that has itself been wire
     * round-tripped (as on a remote agent node) still builds a
     * dispatch-capable job. This composes the factory-wire fix with the
     * factory→job propagation.
     */
    @Test(timeout = 10000)
    public void wireRoundTrippedFactoryStillBuildsDispatchCapableJob() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory();
        original.setPrompts("orchestrate");
        original.setDispatchCapable(true);
        CodingAgentJob.Factory restored =
                GitManagedJobSerializationTest.roundTripFactory(original);
        Job next = restored.nextJob();
        assertTrue("A wire-restored dispatch-capable factory must still build a"
                + " dispatch-capable job", ((CodingAgentJob) next).isDispatchCapable());
    }

    // ------------------------------------------------------------------
    // Job codec: the job's own encode/decode emits ::dispatchCapable:=true
    // only when set, and decodes it back.
    // ------------------------------------------------------------------

    /** The job encode emits {@code ::dispatchCapable:=true} when the flag is set. */
    @Test(timeout = 10000)
    public void jobEncodeEmitsDispatchCapableWhenTrue() {
        CodingAgentJob job = new CodingAgentJob("t-disp", "p");
        job.setDispatchCapable(true);
        String encoded = job.encode();
        assertTrue("encoded job must carry ::dispatchCapable:=true : " + encoded,
                encoded.contains("::dispatchCapable:=true"));
    }

    /** The job encode omits the dispatchCapable key when the flag is false (the default). */
    @Test(timeout = 10000)
    public void jobEncodeOmitsDispatchCapableWhenFalse() {
        CodingAgentJob job = new CodingAgentJob("t-nodisp", "p");
        String encoded = job.encode();
        assertFalse("A non-dispatch job must not emit the dispatchCapable key,"
                + " keeping the wire minimal: " + encoded,
                encoded.contains("dispatchCapable"));
    }

    /** The job's dispatchCapable flag survives a full encode/decode round-trip. */
    @Test(timeout = 10000)
    public void jobDispatchCapableRoundTripsThroughEncodeDecode() {
        CodingAgentJob original = new CodingAgentJob("t-rt", "p");
        original.setDispatchCapable(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertTrue("dispatchCapable=true must survive the job wire round-trip",
                restored.isDispatchCapable());
    }

    /** A non-dispatch job round-trips to a non-dispatch job. */
    @Test(timeout = 10000)
    public void jobNonDispatchRoundTripsFalse() {
        CodingAgentJob original = new CodingAgentJob("t-rt2", "p");
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        assertFalse("A non-dispatch job must remain non-dispatch after a"
                + " wire round-trip", restored.isDispatchCapable());
    }

    /** The codec's {@code applySetting} path decodes the dispatchCapable key via {@code set()}. */
    @Test(timeout = 10000)
    public void jobSetDecodesDispatchCapableKey() {
        CodingAgentJob job = new CodingAgentJob();
        job.set("dispatchCapable", "true");
        assertTrue("set(\"dispatchCapable\", \"true\") must route through the codec"
                + " applySetting branch and flip the flag", job.isDispatchCapable());
    }

    // ------------------------------------------------------------------
    // End-to-end CSV: the GENERATED allowed-tools CSV a dispatch-capable
    // job hands to the agent contains the dispatch tools; a non-dispatch
    // job's does not; destructive admin tools never appear.
    // ------------------------------------------------------------------

    /**
     * The real artifact: a dispatch-capable job composes an allowed-tools
     * CSV containing both dispatch tools. This is the CSV the Claude-Code
     * harness hands to the agent, so it directly determines whether the
     * orchestrator can call {@code workstream_register}.
     */
    @Test(timeout = 30000)
    public void dispatchCapableJobComposesCsvWithDispatchTools() {
        CodingAgentJob job = dispatchJob(true);
        String csv = job.buildComposedAllowedTools();
        assertTrue("dispatch-capable CSV must contain workstream_register",
                csv.contains(PREFIX + "workstream_register"));
        assertTrue("dispatch-capable CSV must contain workstream_update_config",
                csv.contains(PREFIX + "workstream_update_config"));
        // The base ar-manager entries are preserved alongside the overlay.
        assertTrue("dispatch-capable CSV must still contain send_message",
                csv.contains(PREFIX + "send_message"));
    }

    /** A non-dispatch job's CSV excludes the dispatch tools but keeps the base entries. */
    @Test(timeout = 30000)
    public void nonDispatchJobComposesCsvWithoutDispatchTools() {
        CodingAgentJob job = dispatchJob(false);
        String csv = job.buildComposedAllowedTools();
        assertFalse("non-dispatch CSV must NOT contain workstream_register",
                csv.contains(PREFIX + "workstream_register"));
        assertFalse("non-dispatch CSV must NOT contain workstream_update_config",
                csv.contains(PREFIX + "workstream_update_config"));
        assertTrue("non-dispatch CSV must still contain the base send_message tool",
                csv.contains(PREFIX + "send_message"));
    }

    /**
     * Even with the flag set, the dispatch overlay never unlocks
     * destructive / admin tools. This is the security floor: the flag
     * grants register + update only, never delete / archive /
     * workspace-config / secrets.
     */
    @Test(timeout = 30000)
    public void dispatchCapableCsvNeverContainsDestructiveAdminTools() {
        CodingAgentJob job = dispatchJob(true);
        String csv = job.buildComposedAllowedTools();
        assertFalse("dispatch CSV must never contain workstream_delete",
                csv.contains(PREFIX + "workstream_delete"));
        assertFalse("dispatch CSV must never contain workstream_archive",
                csv.contains(PREFIX + "workstream_archive"));
        assertFalse("dispatch CSV must never contain workstream_unarchive",
                csv.contains(PREFIX + "workstream_unarchive"));
        assertFalse("dispatch CSV must never contain workspace_update_config",
                csv.contains(PREFIX + "workspace_update_config"));
        assertFalse("dispatch CSV must never contain controller_update_config",
                csv.contains(PREFIX + "controller_update_config"));
        assertFalse("dispatch CSV must never contain workspace_secret_render_file",
                csv.contains(PREFIX + "workspace_secret_render_file"));
    }

    /**
     * Toggling the job's flag re-composes the CSV both ways: the grant is
     * not sticky-once-on. This mirrors an operator revoking dispatch.
     */
    @Test(timeout = 30000)
    public void jobToggleDispatchCapableRecomposesCsv() {
        CodingAgentJob job = dispatchJob(true);
        assertTrue(job.buildComposedAllowedTools().contains(PREFIX + "workstream_register"));

        // doWork() re-runs configureMcpBuilder() before composing the CSV on
        // every pass, so the builder always reflects the current flag; mirror
        // that here after toggling.
        job.setDispatchCapable(false);
        job.configureMcpBuilder();
        assertFalse("Revoking the flag must drop the dispatch tools from the CSV",
                job.buildComposedAllowedTools().contains(PREFIX + "workstream_register"));
    }

    /**
     * The full end-to-end regression: a dispatch-capable job that has been
     * wire round-tripped (as it would be when shipped to a remote agent
     * node) still composes a CSV containing the dispatch tools. Before the
     * codec fix the round-trip dropped the flag and this CSV silently
     * excluded the dispatch tools — the exact production symptom.
     */
    @Test(timeout = 30000)
    public void wireRoundTrippedJobStillComposesCsvWithDispatchTools() {
        CodingAgentJob original = dispatchJob(true);
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(original);
        // The transient ar-manager URL/token are not part of the job wire
        // form (they are injected per launch); re-apply them so the CSV
        // build emits ar-manager entries, exactly as the launch path does.
        restored.setArManagerUrl(AR_MANAGER_URL);
        restored.setArManagerToken(AR_MANAGER_TOKEN);
        restored.setAllowedTools("Read,Edit");
        // The launch path runs configureMcpBuilder() before composing the CSV;
        // do the same so the restored job's flag reaches the builder.
        restored.configureMcpBuilder();
        String csv = restored.buildComposedAllowedTools();
        assertTrue("A wire-restored dispatch-capable job must still grant"
                + " workstream_register in its composed CSV",
                csv.contains(PREFIX + "workstream_register"));
        assertTrue("A wire-restored dispatch-capable job must still grant"
                + " workstream_update_config in its composed CSV",
                csv.contains(PREFIX + "workstream_update_config"));
    }

    /**
     * Without ar-manager configured the overlay is inert: a dispatch job
     * with no URL/token composes no ar-manager entries at all, so the flag
     * cannot inject tool entries out of thin air.
     */
    @Test(timeout = 30000)
    public void dispatchCapableJobWithoutArManagerComposesNoDispatchTools() {
        CodingAgentJob job = new CodingAgentJob("t-noarm", "p");
        job.setAllowedTools("Read,Edit");
        job.setDispatchCapable(true);
        // No ar-manager URL/token set.
        job.configureMcpBuilder();
        String csv = job.buildComposedAllowedTools();
        assertFalse("Without ar-manager configured, the dispatch flag must not"
                + " inject workstream_register", csv.contains(PREFIX + "workstream_register"));
    }

    /**
     * Builds a {@link CodingAgentJob} configured exactly as the launch path
     * does for the dispatch-capable plumbing: base tools, ar-manager
     * URL/token, the dispatch flag, and a primed MCP builder.
     *
     * @param dispatchCapable whether the job's workstream is dispatch-capable
     * @return a job whose {@link CodingAgentJob#buildComposedAllowedTools()}
     *         reflects the dispatch flag
     */
    private static CodingAgentJob dispatchJob(boolean dispatchCapable) {
        CodingAgentJob job = new CodingAgentJob("t-csv", "p");
        job.setAllowedTools("Read,Edit");
        job.setArManagerUrl(AR_MANAGER_URL);
        job.setArManagerToken(AR_MANAGER_TOKEN);
        job.setDispatchCapable(dispatchCapable);
        job.configureMcpBuilder();
        return job;
    }
}
