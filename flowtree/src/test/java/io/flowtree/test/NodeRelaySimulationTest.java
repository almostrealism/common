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

package io.flowtree.test;

import io.flowtree.Server;
import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end simulation tests for FlowTree relay behaviour across multiple
 * {@link Server} / {@link NodeGroup} instances connected via real TCP loopback
 * sockets.
 *
 * <p>50 unique scenarios span five network shapes:</p>
 * <ul>
 *   <li><b>Shape A</b> — 2 NodeGroups (1 controller + 1 agent), tests a01–a10</li>
 *   <li><b>Shape B</b> — 3 NodeGroups (1 controller + 2 agents), tests b01–b10</li>
 *   <li><b>Shape C</b> — 4 NodeGroups (1 controller + 3 agents), tests c01–c10</li>
 *   <li><b>Shape D</b> — 5 NodeGroups (1 controller + 4 agents), tests d01–d10</li>
 *   <li><b>Shape E</b> — 2–3 NodeGroups with 6–10 worker nodes, tests e01–e10</li>
 * </ul>
 *
 * <p>Each test uses {@link TrackingJob} — a {@link Job} that signals a
 * {@link CountDownLatch} on execution. Jobs are submitted to the controller relay
 * node and must be relayed to and executed by an agent worker node.</p>
 *
 * <p>Startup order is critical: the controller is constructed and started before
 * each agent is constructed, so that the controller's socket accept thread is
 * already running when the agent's {@link NodeGroup} constructor opens the
 * outbound TCP connection and the {@code NodeProxy} handshake can complete.</p>
 *
 * <p>All tests run in CI with no skip guard. Node activity threads are accelerated
 * via reflection (minSleep = 400 ms) so the suite completes in reasonable time.</p>
 *
 * @author Michael Murray
 * @see Node
 * @see NodeGroup
 * @see <a href="../docs/node-relay.md">Node Relay and Job Routing</a>
 */
public class NodeRelaySimulationTest extends ServerTestBase {

    /**
     * JVM-global latch registry shared across all {@link Server} instances in the
     * same test JVM. {@link TrackingJob} looks up its latch by task ID when
     * executed, regardless of which Server thread runs it.
     */
    public static final ConcurrentHashMap<String, CountDownLatch> LATCHES =
            new ConcurrentHashMap<>();

    /** Port counter shared across all tests in this class. */
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(19100);

    // =========================================================================
    // Shape A — 2 NodeGroups (controller + 1 agent)
    // =========================================================================

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node, role:relay]
     *       │
     *       │ TCP + peer connection
     *       ▼
     *   Agent [node0, node1]  (no label requirements)
     * </pre>
     *
     * <p>Scenario A01: 1 job, no label requirements, agent has 2 worker nodes.</p>
     * <p><b>Expected:</b> Controller relay node holds job, establishes peer with
     * agent, relays job. Either worker node executes it.</p>
     * <p><b>Pass:</b> CountDownLatch reaches zero within timeout.</p>
     * <p><b>Fail:</b> Job stuck in relay queue or never delivered to agent.</p>
     */
    @Test(timeout = 20000)
    public void a01_singleJob_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("a01",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("a01: Job should have been executed by agent worker", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1, node2]  (no label requirements)
     * </pre>
     *
     * <p>Scenario A02: 3 jobs, no labels, agent has 3 worker nodes.
     * All three jobs must be executed (potentially in parallel).</p>
     * <p><b>Pass:</b> All 3 latches reach zero.</p>
     * <p><b>Fail:</b> Any job is lost or permanently queued.</p>
     */
    @Test(timeout = 20000)
    public void a02_threeJobs_3nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("a02",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                3, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("a02: All 3 jobs should have been executed", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario A03: 1 job requiring {@code platform:linux}. Agent nodes are
     * labeled linux. The relay node never executes (role:relay). Agent executes.</p>
     * <p><b>Pass:</b> Latch reaches zero — label check passed, job executed.</p>
     * <p><b>Fail:</b> Job stuck due to unresolvable label mismatch.</p>
     */
    @Test(timeout = 20000)
    public void a03_singleJob_2nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("a03",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                1, labels, 15);
        Assert.assertTrue("a03: Label-matching job should execute on linux agent", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1]  (no label requirements)
     * </pre>
     *
     * <p>Scenario A04: 5 jobs, no labels, 2 worker nodes.
     * Tests that the relay loop drains a multi-job queue.</p>
     * <p><b>Pass:</b> All 5 jobs executed.</p>
     * <p><b>Fail:</b> Fewer than 5 jobs execute within timeout.</p>
     */
    @Test(timeout = 25000)
    public void a04_fiveJobs_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("a04",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                5, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("a04: All 5 jobs should have been executed", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1]  (tier:gpu)
     * </pre>
     *
     * <p>Scenario A05: 1 job requiring {@code tier:gpu}. Agent nodes labeled gpu.</p>
     * <p><b>Pass:</b> Latch reaches zero — gpu-labeled agent executed the job.</p>
     * <p><b>Fail:</b> Relay cannot deliver a job with a non-standard label.</p>
     */
    @Test(timeout = 20000)
    public void a05_singleJob_2nodes_tierGpu() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("tier", "gpu");
        boolean done = runScenario("a05",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:gpu")),
                1, labels, 15);
        Assert.assertTrue("a05: gpu-labeled job should execute on gpu agent", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node3]  (no label requirements, 4 workers)
     * </pre>
     *
     * <p>Scenario A06: 8 jobs, no labels, 4 worker nodes.
     * Verifies high-throughput drain across many workers.</p>
     * <p><b>Pass:</b> All 8 jobs executed.</p>
     * <p><b>Fail:</b> Queue not fully drained.</p>
     */
    @Test(timeout = 25000)
    public void a06_eightJobs_4nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("a06",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 4)),
                8, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("a06: All 8 jobs should have been executed", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node4]  (platform:linux, 5 workers)
     * </pre>
     *
     * <p>Scenario A07: 3 jobs with {@code platform:linux} label, 5 linux worker nodes.</p>
     * <p><b>Pass:</b> All 3 jobs executed.</p>
     * <p><b>Fail:</b> Label-based routing breaks with many matching nodes.</p>
     */
    @Test(timeout = 20000)
    public void a07_threeJobs_5nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("a07",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 5, "platform:linux")),
                3, labels, 15);
        Assert.assertTrue("a07: All 3 linux-labeled jobs should execute", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1]  (env:test)
     * </pre>
     *
     * <p>Scenario A08: 1 job with {@code env:test} label.</p>
     * <p><b>Pass:</b> Latch reaches zero.</p>
     * <p><b>Fail:</b> env-label routing fails.</p>
     */
    @Test(timeout = 20000)
    public void a08_singleJob_2nodes_envTest() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("env", "test");
        boolean done = runScenario("a08",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:test")),
                1, labels, 15);
        Assert.assertTrue("a08: env:test job should execute on labeled agent", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1, node2]  (no labels, 3 workers)
     * </pre>
     *
     * <p>Scenario A09: 10 jobs, no labels, 3 workers. Stress-tests relay throughput.</p>
     * <p><b>Pass:</b> All 10 jobs executed within extended timeout.</p>
     * <p><b>Fail:</b> Relay loop cannot handle sustained load.</p>
     */
    @Test(timeout = 30000)
    public void a09_tenJobs_3nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("a09",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                10, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("a09: All 10 jobs should have been executed", done);
    }

    /**
     * Shape A — 2 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario A10: 5 jobs with {@code platform:linux}, 2 linux workers.
     * Tests sustained labeled-job delivery.</p>
     * <p><b>Pass:</b> All 5 jobs executed.</p>
     * <p><b>Fail:</b> Label routing breaks under load.</p>
     */
    @Test(timeout = 25000)
    public void a10_fiveJobs_2nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("a10",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                5, labels, 20);
        Assert.assertTrue("a10: All 5 linux-labeled jobs should execute", done);
    }

    // =========================================================================
    // Shape B — 3 NodeGroups (controller + 2 agents)
    // =========================================================================

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├──────────────────────────────────┐
     *       │ peer → Agent1                    │ peer → Agent2
     *       ▼                                  ▼
     *   Agent1 [node0, node1]             Agent2 [node0, node1]
     *   (no labels)                       (no labels)
     * </pre>
     *
     * <p>Scenario B01: 1 job, no labels. Either agent may execute it.</p>
     * <p><b>Pass:</b> Latch reaches zero.</p>
     * <p><b>Fail:</b> Job bounces indefinitely without execution.</p>
     */
    @Test(timeout = 20000)
    public void b01_singleJob_2agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b01",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("b01: Job should execute on one of the two agents", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       └─── Agent2 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario B02: 2 jobs, no labels, 2 agents × 2 nodes.</p>
     * <p><b>Pass:</b> Both jobs executed.</p>
     * <p><b>Fail:</b> Either job is not executed.</p>
     */
    @Test(timeout = 20000)
    public void b02_twoJobs_2agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b02",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                2, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("b02: Both jobs should execute", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       └─── Agent2 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario B03: 4 jobs, no labels. Tests queue draining across 2 agents.</p>
     * <p><b>Pass:</b> All 4 jobs executed.</p>
     * <p><b>Fail:</b> Any job lost or stuck.</p>
     */
    @Test(timeout = 25000)
    public void b03_fourJobs_2agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b03",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                4, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("b03: All 4 jobs should execute across 2 agents", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (no labels, 3 workers)
     *       └─── Agent2 [node0, node1]  (no labels, 2 workers)
     * </pre>
     *
     * <p>Scenario B04: 6 jobs, no labels. Asymmetric agent capacity.</p>
     * <p><b>Pass:</b> All 6 jobs executed.</p>
     * <p><b>Fail:</b> Asymmetric capacity causes delivery failure.</p>
     */
    @Test(timeout = 25000)
    public void b04_sixJobs_2agents_asymmetricNodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b04",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                6, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("b04: All 6 jobs should execute across asymmetric agents", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (platform:linux)
     *       └─── Agent2 [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario B05: 1 job with {@code platform:linux}, both agents labeled linux.</p>
     * <p><b>Pass:</b> Job executes on whichever linux agent receives it.</p>
     * <p><b>Fail:</b> Label check prevents execution on either linux agent.</p>
     */
    @Test(timeout = 20000)
    public void b05_singleJob_2agents_bothLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("b05",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                1, labels, 15);
        Assert.assertTrue("b05: linux job should execute on one of the linux agents", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (platform:linux, 3 workers)
     *       └─── Agent2 [node0..node2]  (platform:linux, 3 workers)
     * </pre>
     *
     * <p>Scenario B06: 4 jobs with {@code platform:linux}, both agents linux, 3 nodes each.</p>
     * <p><b>Pass:</b> All 4 jobs executed.</p>
     * <p><b>Fail:</b> Label routing combined with 2-agent fan-out drops jobs.</p>
     */
    @Test(timeout = 25000)
    public void b06_fourJobs_2agents_bothLinux_3nodes() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("b06",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "platform:linux")),
                4, labels, 20);
        Assert.assertTrue("b06: All 4 linux jobs should execute", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (no labels, 3 workers)
     *       └─── Agent2 [node0..node2]  (no labels, 3 workers)
     * </pre>
     *
     * <p>Scenario B07: 8 jobs, no labels, 2 agents × 3 nodes. Load test.</p>
     * <p><b>Pass:</b> All 8 jobs executed.</p>
     * <p><b>Fail:</b> Sustained load overwhelms relay queue.</p>
     */
    @Test(timeout = 30000)
    public void b07_eightJobs_2agents_3nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b07",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                8, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("b07: All 8 jobs should execute", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (env:test)
     *       └─── Agent2 [node0, node1]  (env:test)
     * </pre>
     *
     * <p>Scenario B08: 2 jobs with {@code env:test}, both agents labeled test.</p>
     * <p><b>Pass:</b> Both jobs execute on env:test agents.</p>
     * <p><b>Fail:</b> env-label routing fails in multi-agent topology.</p>
     */
    @Test(timeout = 20000)
    public void b08_twoJobs_2agents_envTest() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("env", "test");
        boolean done = runScenario("b08",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:test"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:test")),
                2, labels, 15);
        Assert.assertTrue("b08: Both env:test jobs should execute", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node3]  (no labels, 4 workers)
     *       └─── Agent2 [node0, node1]  (no labels, 2 workers)
     * </pre>
     *
     * <p>Scenario B09: 5 jobs, no labels, very asymmetric agents (4 vs 2 nodes).</p>
     * <p><b>Pass:</b> All 5 jobs executed.</p>
     * <p><b>Fail:</b> Large capacity imbalance causes loss.</p>
     */
    @Test(timeout = 25000)
    public void b09_fiveJobs_2agents_asymmetric_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("b09",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                5, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("b09: All 5 jobs should execute", done);
    }

    /**
     * Shape B — 3 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (tier:compute, 3 workers)
     *       └─── Agent2 [node0..node2]  (tier:compute, 3 workers)
     * </pre>
     *
     * <p>Scenario B10: 3 jobs with {@code tier:compute}, both agents labeled.</p>
     * <p><b>Pass:</b> All 3 compute jobs execute.</p>
     * <p><b>Fail:</b> tier label not respected in 3-NodeGroup topology.</p>
     */
    @Test(timeout = 20000)
    public void b10_threeJobs_2agents_tierCompute() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("tier", "compute");
        boolean done = runScenario("b10",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "tier:compute"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "tier:compute")),
                3, labels, 15);
        Assert.assertTrue("b10: All 3 compute jobs should execute", done);
    }

    // =========================================================================
    // Shape C — 4 NodeGroups (controller + 3 agents)
    // =========================================================================

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       └─── Agent3 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario C01: 1 job, no labels, 3 agents × 2 nodes.</p>
     * <p><b>Pass:</b> Latch reaches zero — relay selects one of 3 agents.</p>
     * <p><b>Fail:</b> 3-agent fan-out prevents delivery.</p>
     */
    @Test(timeout = 20000)
    public void c01_singleJob_3agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c01",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("c01: Job should execute on one of 3 agents", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       └─── Agent3 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario C02: 3 jobs, no labels. One job per agent if routing distributes.</p>
     * <p><b>Pass:</b> All 3 jobs executed.</p>
     * <p><b>Fail:</b> Any job lost with 3-agent topology.</p>
     */
    @Test(timeout = 20000)
    public void c02_threeJobs_3agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c02",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                3, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("c02: All 3 jobs should execute", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       └─── Agent3 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario C03: 6 jobs, no labels. 2 jobs per agent at equal capacity.</p>
     * <p><b>Pass:</b> All 6 jobs executed.</p>
     * <p><b>Fail:</b> Load distribution fails across 3 agents.</p>
     */
    @Test(timeout = 25000)
    public void c03_sixJobs_3agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c03",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                6, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("c03: All 6 jobs should execute across 3 agents", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (platform:linux)
     *       ├─── Agent2 [node0, node1]  (platform:linux)
     *       └─── Agent3 [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario C04: 1 job with {@code platform:linux}, all 3 agents linux.</p>
     * <p><b>Pass:</b> Job executes on any of the 3 linux agents.</p>
     * <p><b>Fail:</b> Label routing fails with 3-agent linux pool.</p>
     */
    @Test(timeout = 20000)
    public void c04_singleJob_3agents_allLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("c04",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                1, labels, 15);
        Assert.assertTrue("c04: linux job should execute on one of 3 linux agents", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (platform:linux, 3 workers)
     *       ├─── Agent2 [node0..node2]  (platform:linux, 3 workers)
     *       └─── Agent3 [node0..node2]  (platform:linux, 3 workers)
     * </pre>
     *
     * <p>Scenario C05: 4 jobs with {@code platform:linux}, all linux, 3 nodes each.</p>
     * <p><b>Pass:</b> All 4 jobs executed.</p>
     * <p><b>Fail:</b> Label routing + 3-agent + 3-node combination breaks.</p>
     */
    @Test(timeout = 25000)
    public void c05_fourJobs_3agents_allLinux_3nodes() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("c05",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "platform:linux")),
                4, labels, 20);
        Assert.assertTrue("c05: All 4 linux jobs should execute", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (no labels, 3 workers)
     *       ├─── Agent2 [node0..node2]  (no labels, 3 workers)
     *       └─── Agent3 [node0..node2]  (no labels, 3 workers)
     * </pre>
     *
     * <p>Scenario C06: 9 jobs, no labels, 3 agents × 3 nodes. Grid throughput.</p>
     * <p><b>Pass:</b> All 9 jobs executed.</p>
     * <p><b>Fail:</b> 3×3 grid under sustained load.</p>
     */
    @Test(timeout = 30000)
    public void c06_nineJobs_3agents_3nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c06",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                9, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("c06: All 9 jobs should execute in 3×3 grid", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (env:prod)
     *       ├─── Agent2 [node0, node1]  (env:prod)
     *       └─── Agent3 [node0, node1]  (env:prod)
     * </pre>
     *
     * <p>Scenario C07: 2 jobs with {@code env:prod}, all agents prod-labeled.</p>
     * <p><b>Pass:</b> Both jobs execute.</p>
     * <p><b>Fail:</b> env:prod label routing fails at 3-agent scale.</p>
     */
    @Test(timeout = 20000)
    public void c07_twoJobs_3agents_envProd() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("env", "prod");
        boolean done = runScenario("c07",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:prod"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:prod"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "env:prod")),
                2, labels, 15);
        Assert.assertTrue("c07: Both env:prod jobs should execute", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]   (no labels, 2 workers)
     *       ├─── Agent2 [node0..node2]   (no labels, 3 workers)
     *       └─── Agent3 [node0, node1]   (no labels, 2 workers)
     * </pre>
     *
     * <p>Scenario C08: 6 jobs, no labels, agents with 2/3/2 node counts.</p>
     * <p><b>Pass:</b> All 6 jobs executed despite uneven capacity.</p>
     * <p><b>Fail:</b> Uneven capacity in 3-agent topology causes loss.</p>
     */
    @Test(timeout = 25000)
    public void c08_sixJobs_3agents_unevenNodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c08",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                6, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("c08: All 6 jobs should execute with 2/3/2 node counts", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (tier:gpu)
     *       ├─── Agent2 [node0, node1]  (tier:gpu)
     *       └─── Agent3 [node0, node1]  (tier:gpu)
     * </pre>
     *
     * <p>Scenario C09: 4 jobs with {@code tier:gpu}, all agents labeled gpu.</p>
     * <p><b>Pass:</b> All 4 gpu jobs execute.</p>
     * <p><b>Fail:</b> gpu label lost in 4-NodeGroup topology.</p>
     */
    @Test(timeout = 25000)
    public void c09_fourJobs_3agents_tierGpu() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("tier", "gpu");
        boolean done = runScenario("c09",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:gpu"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:gpu"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:gpu")),
                4, labels, 20);
        Assert.assertTrue("c09: All 4 gpu-labeled jobs should execute", done);
    }

    /**
     * Shape C — 4 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node3]  (no labels, 4 workers)
     *       ├─── Agent2 [node0..node3]  (no labels, 4 workers)
     *       └─── Agent3 [node0..node3]  (no labels, 4 workers)
     * </pre>
     *
     * <p>Scenario C10: 12 jobs, no labels, 3 agents × 4 nodes.
     * Maximum throughput test for 4-NodeGroup shape.</p>
     * <p><b>Pass:</b> All 12 jobs executed.</p>
     * <p><b>Fail:</b> High job count + 3 agents causes delivery failure.</p>
     */
    @Test(timeout = 35000)
    public void c10_twelveJobs_3agents_4nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("c10",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4)),
                12, Collections.<String, String>emptyMap(), 30);
        Assert.assertTrue("c10: All 12 jobs should execute in 3×4 grid", done);
    }

    // =========================================================================
    // Shape D — 5 NodeGroups (controller + 4 agents)
    // =========================================================================

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       ├─── Agent3 [node0, node1]  (no labels)
     *       └─── Agent4 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario D01: 1 job, no labels, 4-agent star topology.</p>
     * <p><b>Pass:</b> Job executes on one of 4 agents.</p>
     * <p><b>Fail:</b> 5-NodeGroup star prevents delivery.</p>
     */
    @Test(timeout = 20000)
    public void d01_singleJob_4agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d01",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("d01: Job should execute on one of 4 agents", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       ├─── Agent3 [node0, node1]  (no labels)
     *       └─── Agent4 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario D02: 4 jobs, no labels. Ideally one per agent.</p>
     * <p><b>Pass:</b> All 4 jobs execute.</p>
     * <p><b>Fail:</b> 4-way fan-out cannot deliver 4 jobs.</p>
     */
    @Test(timeout = 25000)
    public void d02_fourJobs_4agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d02",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                4, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("d02: All 4 jobs should execute across 4 agents", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (no labels)
     *       ├─── Agent2 [node0, node1]  (no labels)
     *       ├─── Agent3 [node0, node1]  (no labels)
     *       └─── Agent4 [node0, node1]  (no labels)
     * </pre>
     *
     * <p>Scenario D03: 8 jobs, no labels, 4 agents × 2 nodes.</p>
     * <p><b>Pass:</b> All 8 jobs executed.</p>
     * <p><b>Fail:</b> Load distribution breaks at 8 jobs.</p>
     */
    @Test(timeout = 30000)
    public void d03_eightJobs_4agents_2nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d03",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2)),
                8, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("d03: All 8 jobs should execute", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (no labels, 3 workers)
     *       ├─── Agent2 [node0..node2]  (no labels, 3 workers)
     *       ├─── Agent3 [node0..node2]  (no labels, 3 workers)
     *       └─── Agent4 [node0..node2]  (no labels, 3 workers)
     * </pre>
     *
     * <p>Scenario D04: 12 jobs, no labels, 4 agents × 3 nodes.</p>
     * <p><b>Pass:</b> All 12 jobs executed.</p>
     * <p><b>Fail:</b> 4×3 capacity not fully utilised.</p>
     */
    @Test(timeout = 35000)
    public void d04_twelveJobs_4agents_3nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d04",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                12, Collections.<String, String>emptyMap(), 30);
        Assert.assertTrue("d04: All 12 jobs should execute in 4×3 grid", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (platform:linux)
     *       ├─── Agent2 [node0, node1]  (platform:linux)
     *       ├─── Agent3 [node0, node1]  (platform:linux)
     *       └─── Agent4 [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario D05: 1 job with {@code platform:linux}, all 4 agents linux.</p>
     * <p><b>Pass:</b> Job executes on any linux agent.</p>
     * <p><b>Fail:</b> Label routing fails in 5-NodeGroup star.</p>
     */
    @Test(timeout = 20000)
    public void d05_singleJob_4agents_allLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("d05",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                1, labels, 15);
        Assert.assertTrue("d05: linux job should execute on one of 4 linux agents", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (platform:linux)
     *       ├─── Agent2 [node0, node1]  (platform:linux)
     *       ├─── Agent3 [node0, node1]  (platform:linux)
     *       └─── Agent4 [node0, node1]  (platform:linux)
     * </pre>
     *
     * <p>Scenario D06: 4 labeled linux jobs, 4 linux agents.</p>
     * <p><b>Pass:</b> All 4 linux jobs execute.</p>
     * <p><b>Fail:</b> Label routing under 4-job load in 5-NodeGroup star.</p>
     */
    @Test(timeout = 25000)
    public void d06_fourJobs_4agents_allLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("d06",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "platform:linux")),
                4, labels, 20);
        Assert.assertTrue("d06: All 4 linux jobs should execute across 4 agents", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node3]  (no labels, 4 workers)
     *       ├─── Agent2 [node0..node3]  (no labels, 4 workers)
     *       ├─── Agent3 [node0..node3]  (no labels, 4 workers)
     *       └─── Agent4 [node0..node3]  (no labels, 4 workers)
     * </pre>
     *
     * <p>Scenario D07: 16 jobs, no labels, 4 agents × 4 nodes. High-throughput test.</p>
     * <p><b>Pass:</b> All 16 jobs executed.</p>
     * <p><b>Fail:</b> 4×4 grid saturated before all jobs complete.</p>
     */
    @Test(timeout = 40000)
    public void d07_sixteenJobs_4agents_4nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d07",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 4)),
                16, Collections.<String, String>emptyMap(), 35);
        Assert.assertTrue("d07: All 16 jobs should execute in 4×4 grid", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node2]  (env:test, 3 workers)
     *       ├─── Agent2 [node0..node2]  (env:test, 3 workers)
     *       ├─── Agent3 [node0..node2]  (env:test, 3 workers)
     *       └─── Agent4 [node0..node2]  (env:test, 3 workers)
     * </pre>
     *
     * <p>Scenario D08: 6 jobs with {@code env:test}, all agents labeled, 3 nodes each.</p>
     * <p><b>Pass:</b> All 6 jobs execute.</p>
     * <p><b>Fail:</b> env:test label routing breaks at maximum NodeGroup count.</p>
     */
    @Test(timeout = 30000)
    public void d08_sixJobs_4agents_envTest_3nodes() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("env", "test");
        boolean done = runScenario("d08",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "env:test"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "env:test"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "env:test"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3, "env:test")),
                6, labels, 25);
        Assert.assertTrue("d08: All 6 env:test jobs should execute", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]  (tier:compute)
     *       ├─── Agent2 [node0, node1]  (tier:compute)
     *       ├─── Agent3 [node0, node1]  (tier:compute)
     *       └─── Agent4 [node0, node1]  (tier:compute)
     * </pre>
     *
     * <p>Scenario D09: 2 jobs with {@code tier:compute}, all agents labeled.</p>
     * <p><b>Pass:</b> Both jobs execute on compute-labeled agents.</p>
     * <p><b>Fail:</b> Label routing fails with 4-agent compute pool.</p>
     */
    @Test(timeout = 20000)
    public void d09_twoJobs_4agents_tierCompute() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("tier", "compute");
        boolean done = runScenario("d09",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:compute"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:compute"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:compute"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2, "tier:compute")),
                2, labels, 15);
        Assert.assertTrue("d09: Both tier:compute jobs should execute", done);
    }

    /**
     * Shape D — 5 NodeGroups.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0, node1]   (no labels, 2 workers)
     *       ├─── Agent2 [node0, node1]   (no labels, 2 workers)
     *       ├─── Agent3 [node0..node2]   (no labels, 3 workers)
     *       └─── Agent4 [node0..node2]   (no labels, 3 workers)
     * </pre>
     *
     * <p>Scenario D10: 20 jobs, no labels, mixed agent capacity (2/2/3/3).
     * Maximum-load test for Shape D.</p>
     * <p><b>Pass:</b> All 20 jobs executed.</p>
     * <p><b>Fail:</b> High load + heterogeneous agents loses jobs.</p>
     */
    @Test(timeout = 45000)
    public void d10_twentyJobs_4agents_mixedNodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("d10",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 2),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 3)),
                20, Collections.<String, String>emptyMap(), 40);
        Assert.assertTrue("d10: All 20 jobs should execute with mixed agent capacity", done);
    }

    // =========================================================================
    // Shape E — 2–3 NodeGroups with high per-agent node counts (6–10 nodes)
    // =========================================================================

    /**
     * Shape E — 2 NodeGroups, 10 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node9]  (no labels, 10 workers)
     * </pre>
     *
     * <p>Scenario E01: 1 job, no labels, maximum-width single agent.</p>
     * <p><b>Pass:</b> Job executes on one of 10 workers.</p>
     * <p><b>Fail:</b> Wide agent NodeGroup cannot receive relayed jobs.</p>
     */
    @Test(timeout = 20000)
    public void e01_singleJob_1agent_10nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e01",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 10)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("e01: Job should execute on 10-node agent", done);
    }

    /**
     * Shape E — 2 NodeGroups, 10 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node9]  (no labels, 10 workers)
     * </pre>
     *
     * <p>Scenario E02: 5 jobs, no labels, 10-node agent. Parallel drain.</p>
     * <p><b>Pass:</b> All 5 jobs executed concurrently across 10 nodes.</p>
     * <p><b>Fail:</b> Wide agent saturates.</p>
     */
    @Test(timeout = 25000)
    public void e02_fiveJobs_1agent_10nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e02",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 10)),
                5, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("e02: All 5 jobs should execute on 10-node agent", done);
    }

    /**
     * Shape E — 2 NodeGroups, 8 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node7]  (no labels, 8 workers)
     * </pre>
     *
     * <p>Scenario E03: 1 job, no labels, 8-node agent.</p>
     * <p><b>Pass:</b> Job executes on one of 8 nodes.</p>
     * <p><b>Fail:</b> 8-wide agent routing fails.</p>
     */
    @Test(timeout = 20000)
    public void e03_singleJob_1agent_8nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e03",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 8)),
                1, Collections.<String, String>emptyMap(), 15);
        Assert.assertTrue("e03: Job should execute on 8-node agent", done);
    }

    /**
     * Shape E — 2 NodeGroups, 8 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node7]  (platform:linux, 8 workers)
     * </pre>
     *
     * <p>Scenario E04: 3 jobs with {@code platform:linux}, 8 linux workers.</p>
     * <p><b>Pass:</b> All 3 labeled jobs execute.</p>
     * <p><b>Fail:</b> Label routing broken with wide node pool.</p>
     */
    @Test(timeout = 20000)
    public void e04_threeJobs_1agent_8nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("e04",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 8, "platform:linux")),
                3, labels, 15);
        Assert.assertTrue("e04: All 3 linux jobs should execute on 8-node agent", done);
    }

    /**
     * Shape E — 3 NodeGroups, 8 and 6 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node7]  (no labels, 8 workers)
     *       └─── Agent2 [node0..node5]  (no labels, 6 workers)
     * </pre>
     *
     * <p>Scenario E05: 4 jobs, no labels, large asymmetric agents (8 vs 6 nodes).</p>
     * <p><b>Pass:</b> All 4 jobs executed across large agent pool.</p>
     * <p><b>Fail:</b> Delivery fails with very large node counts.</p>
     */
    @Test(timeout = 25000)
    public void e05_fourJobs_2agents_8and6nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e05",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 8),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 6)),
                4, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("e05: All 4 jobs should execute across 8- and 6-node agents", done);
    }

    /**
     * Shape E — 3 NodeGroups, 10 and 8 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node9]  (no labels, 10 workers)
     *       └─── Agent2 [node0..node7]  (no labels, 8 workers)
     * </pre>
     *
     * <p>Scenario E06: 6 jobs, no labels, very large agent pair (10 and 8 nodes).</p>
     * <p><b>Pass:</b> All 6 jobs executed.</p>
     * <p><b>Fail:</b> 18 total workers cannot handle 6-job burst.</p>
     */
    @Test(timeout = 30000)
    public void e06_sixJobs_2agents_10and8nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e06",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 10),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 8)),
                6, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("e06: All 6 jobs should execute", done);
    }

    /**
     * Shape E — 4 NodeGroups (controller + 3 agents), escalating node counts.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node5]  (no labels, 6 workers)
     *       ├─── Agent2 [node0..node7]  (no labels, 8 workers)
     *       └─── Agent3 [node0..node9]  (no labels, 10 workers)
     * </pre>
     *
     * <p>Scenario E07: 3 jobs, no labels, escalating node counts across 3 agents.</p>
     * <p><b>Pass:</b> All 3 jobs executed.</p>
     * <p><b>Fail:</b> Relay misses large-pool agents.</p>
     */
    @Test(timeout = 25000)
    public void e07_threeJobs_3agents_escalatingNodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e07",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 6),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 8),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 10)),
                3, Collections.<String, String>emptyMap(), 20);
        Assert.assertTrue("e07: All 3 jobs should execute across escalating-size agents", done);
    }

    /**
     * Shape E — 2 NodeGroups, 9 worker nodes.
     *
     * <pre>
     *   Controller [relay node]
     *       │
     *       ▼
     *   Agent [node0..node8]  (platform:linux, 9 workers)
     * </pre>
     *
     * <p>Scenario E08: 9 jobs with {@code platform:linux}, 9-node agent.</p>
     * <p><b>Pass:</b> All 9 jobs executed.</p>
     * <p><b>Fail:</b> Relay fails to drain all jobs into a 9-node pool.</p>
     */
    @Test(timeout = 30000)
    public void e08_nineJobs_1agent_9nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("e08",
                controllerProps(cp),
                agentPropsList(agentProps(NEXT_PORT.getAndIncrement(), cp, 9, "platform:linux")),
                9, labels, 25);
        Assert.assertTrue("e08: All 9 linux jobs should execute on 9-node agent", done);
    }

    /**
     * Shape E — 3 NodeGroups, 7 nodes each agent.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node6]  (no labels, 7 workers)
     *       └─── Agent2 [node0..node6]  (no labels, 7 workers)
     * </pre>
     *
     * <p>Scenario E09: 8 jobs, no labels, 2 agents × 7 nodes.</p>
     * <p><b>Pass:</b> All 8 jobs executed across two 7-node agents.</p>
     * <p><b>Fail:</b> Symmetric wide agents fail to receive all relay jobs.</p>
     */
    @Test(timeout = 30000)
    public void e09_eightJobs_2agents_7nodes_noLabels() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        boolean done = runScenario("e09",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 7),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 7)),
                8, Collections.<String, String>emptyMap(), 25);
        Assert.assertTrue("e09: All 8 jobs should execute across two 7-node agents", done);
    }

    /**
     * Shape E — 3 NodeGroups, 10 nodes each agent.
     *
     * <pre>
     *   Controller [relay node]
     *       ├─── Agent1 [node0..node9]  (platform:linux, 10 workers)
     *       └─── Agent2 [node0..node9]  (platform:linux, 10 workers)
     * </pre>
     *
     * <p>Scenario E10: 10 jobs with {@code platform:linux}, 2 agents × 10 nodes.
     * Maximum configuration stress test.</p>
     * <p><b>Pass:</b> All 10 labeled jobs executed across 20 total workers.</p>
     * <p><b>Fail:</b> Maximum-scale configuration loses labeled jobs.</p>
     */
    @Test(timeout = 35000)
    public void e10_tenJobs_2agents_10nodes_platformLinux() throws Exception {
        int cp = NEXT_PORT.getAndIncrement();
        Map<String, String> labels = Collections.singletonMap("platform", "linux");
        boolean done = runScenario("e10",
                controllerProps(cp),
                agentPropsList(
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 10, "platform:linux"),
                        agentProps(NEXT_PORT.getAndIncrement(), cp, 10, "platform:linux")),
                10, labels, 30);
        Assert.assertTrue("e10: All 10 linux jobs should execute across two 10-node agents", done);
    }

    // =========================================================================
    // Infrastructure — helpers and inner classes
    // =========================================================================

    /**
     * Runs a relay scenario end-to-end.
     *
     * <p><b>Startup order is critical:</b> the controller is constructed and started
     * before each agent is constructed. This ensures the controller's accept thread
     * is already calling {@code socket.accept()} when the agent's {@link NodeGroup}
     * constructor opens the outbound TCP connection, so the {@code NodeProxy}
     * handshake (which blocks on {@code ObjectInputStream} header) can complete.</p>
     *
     * @param id             unique scenario identifier used as a job-ID prefix
     * @param controllerP    Properties for the controller Server
     * @param agentPropsList Properties for each agent Server (one per agent)
     * @param jobCount       number of jobs to submit to the controller
     * @param labels         required labels that all submitted jobs carry
     * @param timeoutSec     seconds to wait before declaring failure
     * @return {@code true} if all jobs executed within the timeout
     * @throws Exception on setup or I/O error
     */
    private boolean runScenario(String id, Properties controllerP,
                                List<Properties> agentPropsList,
                                int jobCount, Map<String, String> labels,
                                long timeoutSec) throws Exception {
        // 1. Build and start controller first so its accept thread is listening.
        Server controller = new Server(controllerP);
        speedUpNodes(controller);
        controller.start();

        List<Server> agents = new ArrayList<Server>(agentPropsList.size());

        try {
            // 2. Build and start each agent after the controller is accepting connections.
            for (Properties ap : agentPropsList) {
                Server agent = new Server(ap);
                speedUpNodes(agent);
                agent.start();
                agents.add(agent);
            }

            // 3. Submit jobs via the controller's task mechanism.
            CountDownLatch latch = new CountDownLatch(jobCount);
            List<TrackingJob> jobs = new ArrayList<TrackingJob>(jobCount);
            for (int i = 0; i < jobCount; i++) {
                String jobId = id + "-" + i + "-" +
                        UUID.randomUUID().toString().substring(0, 8);
                jobs.add(new TrackingJob(jobId, latch, labels));
            }
            controller.addTask(new TrackingJobFactory(id, jobs));

            return latch.await(timeoutSec, TimeUnit.SECONDS);

        } finally {
            for (Server agent : agents) {
                try { agent.stop(); } catch (Exception ignored) { }
            }
            try { controller.stop(); } catch (Exception ignored) { }
        }
    }

    /**
     * Returns a properties map for a controller Server.
     * No worker nodes are created ({@code nodes.initial=0}); only the dedicated
     * {@code relayNode} exists. This ensures the relay node is the sole source
     * of peer connections, avoiding the {@code isConnected(proxy)} contention
     * that occurs when a label-tagged worker node also requests connections.
     * {@code nodes.mjp=0.0} ensures relay fires for any non-empty queue.
     *
     * @param port TCP listen port
     * @return Properties for a relay-only controller NodeGroup
     */
    private Properties controllerProps(int port) {
        Properties p = new Properties();
        p.setProperty("server.port", String.valueOf(port));
        p.setProperty("nodes.initial", "0");
        p.setProperty("nodes.jobs.max", "100");
        p.setProperty("nodes.mjp", "0.0");
        p.setProperty("nodes.relay", "1.0");
        p.setProperty("group.thread.sleep", "100");
        return p;
    }

    /**
     * Returns a properties map for an agent Server.
     *
     * @param port           TCP listen port for the agent
     * @param controllerPort port of the controller to connect to at startup
     * @param nodeCount      number of worker Nodes in the agent NodeGroup (2–10)
     * @param labelPairs     optional labels in {@code "key:value"} format applied
     *                       to all worker Nodes
     * @return Properties for an execution-capable agent NodeGroup
     */
    private Properties agentProps(int port, int controllerPort, int nodeCount,
                                  String... labelPairs) {
        Properties p = new Properties();
        p.setProperty("server.port", String.valueOf(port));
        p.setProperty("servers.total", "1");
        p.setProperty("servers.0.host", "localhost");
        p.setProperty("servers.0.port", String.valueOf(controllerPort));
        p.setProperty("nodes.initial", String.valueOf(nodeCount));
        p.setProperty("nodes.jobs.max", "4");
        p.setProperty("nodes.relay", "1.0");
        p.setProperty("nodes.mjp", "0.0");
        p.setProperty("group.thread.sleep", "100");
        for (String pair : labelPairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                p.setProperty("nodes.labels." + kv[0], kv[1]);
            }
        }
        return p;
    }

    /**
     * Varargs helper that wraps agent {@link Properties} in a {@link List}.
     *
     * @param props agent properties in order
     * @return list of agent properties
     */
    private List<Properties> agentPropsList(Properties... props) {
        return Arrays.asList(props);
    }

    /**
     * Accelerates all {@link Node} activity threads in the given {@link Server}
     * by reducing their minimum sleep interval to 400 ms via reflection.
     *
     * <p>This must be called <em>before</em> {@link Server#start()} so that the
     * pre-set sleep values are used when the activity threads begin.</p>
     *
     * @param s the Server whose Nodes should be accelerated
     * @throws ReflectiveOperationException if the private fields cannot be accessed
     */
    private void speedUpNodes(Server s) throws ReflectiveOperationException {
        NodeGroup group = s.getNodeGroup();
        speedUpNode(group);

        for (Node n : group.getNodes()) {
            speedUpNode(n);
        }

        Field relayField = NodeGroup.class.getDeclaredField("relayNode");
        relayField.setAccessible(true);
        speedUpNode((Node) relayField.get(group));
    }

    /**
     * Sets the {@code minSleep} and {@code sleep} fields of a {@link Node} to
     * 400 ms so its activity thread iterates approximately every 400 ms rather
     * than the default 5 000 ms minimum.
     *
     * @param n the Node to accelerate
     * @throws ReflectiveOperationException if field access fails
     */
    private void speedUpNode(Node n) throws ReflectiveOperationException {
        Field minSleepField = Node.class.getDeclaredField("minSleep");
        minSleepField.setAccessible(true);
        minSleepField.setDouble(n, 400.0);

        Field sleepField = Node.class.getDeclaredField("sleep");
        sleepField.setAccessible(true);
        sleepField.setInt(n, 400);
    }

    // =========================================================================
    // TrackingJob
    // =========================================================================

    /**
     * A {@link Job} that signals a JVM-global {@link CountDownLatch} when
     * executed. The latch is registered in {@link #LATCHES} by task ID before
     * submission and looked up during {@link #run()} so that relay across server
     * boundaries (within the same test JVM) is transparently tracked.
     *
     * <p>Encoding format consumed by {@link Server#instantiateJobClass(String)}:</p>
     * <pre>
     * io.flowtree.test.NodeRelaySimulationTest$TrackingJob::id:=&lt;taskId&gt;[::label.&lt;key&gt;:=&lt;value&gt;...]
     * </pre>
     */
    public static class TrackingJob implements Job {

        /** Unique identifier for this job instance. */
        private String taskId;

        /** Required labels that a Node must carry to execute this job. */
        private final Map<String, String> requiredLabels;

        /** CompletableFuture completed when {@link #run()} is called. */
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        /**
         * No-arg constructor required for reflective instantiation by
         * {@link Server#instantiateJobClass(String)} on the receiving node.
         */
        public TrackingJob() {
            this.taskId = "";
            this.requiredLabels = new HashMap<String, String>();
        }

        /**
         * Creates a TrackingJob and registers its latch in the JVM-global map.
         *
         * @param taskId         unique job identifier
         * @param latch          latch to count down when the job executes
         * @param requiredLabels labels the executing Node must carry
         */
        public TrackingJob(String taskId, CountDownLatch latch,
                           Map<String, String> requiredLabels) {
            this.taskId = taskId;
            this.requiredLabels = new HashMap<String, String>(requiredLabels);
            LATCHES.put(taskId, latch);
        }

        /** Counts down the registered latch, marking this job as executed. */
        @Override
        public void run() {
            CountDownLatch latch = LATCHES.get(taskId);
            if (latch != null) {
                latch.countDown();
            }
            future.complete(null);
        }

        /**
         * Restores job state during reflective deserialisation.
         * The key {@code "id"} sets the task ID; keys prefixed with
         * {@code "label."} populate the required-labels map.
         *
         * @param key   property name
         * @param value property value
         */
        @Override
        public void set(String key, String value) {
            if ("id".equals(key)) {
                this.taskId = value;
            } else if (key.startsWith("label.")) {
                this.requiredLabels.put(key.substring(6), value);
            }
        }

        /**
         * Encodes this job as a string consumable by
         * {@link Server#instantiateJobClass(String)}.
         *
         * @return encoded job string
         */
        @Override
        public String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append("io.flowtree.test.NodeRelaySimulationTest$TrackingJob");
            sb.append("::id:=").append(taskId);
            for (Map.Entry<String, String> e : requiredLabels.entrySet()) {
                sb.append("::label.").append(e.getKey())
                        .append(":=").append(e.getValue());
            }
            return sb.toString();
        }

        @Override
        public String getTaskId() { return taskId; }

        @Override
        public String getTaskString() { return "TrackingJob:" + taskId; }

        @Override
        public Map<String, String> getRequiredLabels() {
            return Collections.unmodifiableMap(requiredLabels);
        }

        @Override
        public CompletableFuture<Void> getCompletableFuture() { return future; }
    }

    // =========================================================================
    // TrackingJobFactory
    // =========================================================================

    /**
     * A {@link JobFactory} that produces a fixed list of {@link TrackingJob}s,
     * one per {@link #nextJob()} call, then reports {@link #isComplete()} true.
     */
    static class TrackingJobFactory implements JobFactory {

        private final String taskId;
        private final ArrayDeque<TrackingJob> pending;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        /**
         * Constructs a factory that will emit the given jobs in order.
         *
         * @param taskId unique task identifier
         * @param jobs   jobs to emit; consumed in iteration order
         */
        TrackingJobFactory(String taskId, List<TrackingJob> jobs) {
            this.taskId = taskId;
            this.pending = new ArrayDeque<TrackingJob>(jobs);
        }

        @Override
        public Job nextJob() {
            return pending.poll();
        }

        @Override
        public Job createJob(String data) {
            return Server.instantiateJobClass(data);
        }

        @Override
        public boolean isComplete() { return pending.isEmpty(); }

        @Override
        public double getCompleteness() { return pending.isEmpty() ? 1.0 : 0.0; }

        @Override
        public String getTaskId() { return taskId; }

        @Override
        public String getName() { return "TrackingJobFactory:" + taskId; }

        @Override
        public String encode() {
            return "io.flowtree.test.NodeRelaySimulationTest$TrackingJobFactory::id:=" + taskId;
        }

        @Override
        public void set(String key, String value) { }

        @Override
        public void setPriority(double p) { }

        @Override
        public double getPriority() { return 1.0; }

        @Override
        public CompletableFuture<Void> getCompletableFuture() { return future; }
    }
}
