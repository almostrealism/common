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

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import java.lang.reflect.Field;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for label-based job routing in {@link Node} and {@link NodeGroup}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Jobs with matching labels are executed locally</li>
 *   <li>Jobs with mismatched labels are not executed locally</li>
 *   <li>Jobs with no label requirements run on any Node</li>
 *   <li>{@link NodeGroup#findNodeForJob(Job)} selects only qualified Nodes</li>
 *   <li>Mismatched jobs are relayed, not silently dropped</li>
 * </ul>
 *
 * @author Michael Murray
 */
public class NodeLabelRoutingTest extends TestSuiteBase {

    /**
     * Verifies that a Node executes a job whose required labels
     * match the Node's own labels.
     */
    @Test(timeout = 15000)
    public void matchingLabelsExecuteLocally() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = nodeGroupProperties();
        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        // Set matching labels on the child Node
        Node child = getChildNode(group);
        child.setLabel("platform", "macos");

        CountDownLatch executed = new CountDownLatch(1);
        LabelledJob job = new LabelledJob("match-test",
                Collections.singletonMap("platform", "macos"), executed);

        child.addJob(job);

        Assert.assertTrue("Job with matching labels should have been executed",
                executed.await(10, TimeUnit.SECONDS));
    }

    /**
     * Verifies that a job with no required labels executes on any Node.
     */
    @Test(timeout = 15000)
    public void noLabelRequirementsRunsAnywhere() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = nodeGroupProperties();
        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        Node child = getChildNode(group);
        child.setLabel("platform", "linux");

        CountDownLatch executed = new CountDownLatch(1);
        LabelledJob job = new LabelledJob("any-node-test",
                Collections.emptyMap(), executed);

        child.addJob(job);

        Assert.assertTrue("Job with no label requirements should run on any Node",
                executed.await(10, TimeUnit.SECONDS));
    }

    /**
     * Verifies that {@link NodeGroup#findNodeForJob(Job)} returns null
     * when no child Node satisfies the job's required labels.
     */
    @Test
    public void findNodeForJobReturnsNullWhenNoMatch() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("nodes.initial", "2");
        p.setProperty("nodes.jobs.max", "4");
        p.setProperty("servers.total", "0");
        p.setProperty("group.thread.sleep", "10000");

        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        // Both children are linux
        for (Node n : getChildNodes(group)) {
            n.setLabel("platform", "linux");
        }

        LabelledJob macJob = new LabelledJob("mac-only",
                Collections.singletonMap("platform", "macos"));

        Node result = group.findNodeForJob(macJob);
        Assert.assertNull("findNodeForJob should return null when no Node matches", result);
    }

    /**
     * Verifies that {@link NodeGroup#findNodeForJob(Job)} returns a
     * matching Node when one is available.
     */
    @Test
    public void findNodeForJobReturnsMatchingNode() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("nodes.initial", "2");
        p.setProperty("nodes.jobs.max", "4");
        p.setProperty("servers.total", "0");
        p.setProperty("group.thread.sleep", "10000");

        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        List<Node> children = getChildNodes(group);
        children.get(0).setLabel("platform", "linux");
        children.get(1).setLabel("platform", "macos");

        LabelledJob macJob = new LabelledJob("mac-only",
                Collections.singletonMap("platform", "macos"));

        Node result = group.findNodeForJob(macJob);
        Assert.assertNotNull("findNodeForJob should return a matching Node", result);
        Assert.assertEquals("macos", result.getLabels().get("platform"));
    }

    /**
     * Verifies that {@link NodeGroup#findNodeForJob(Job)} falls back
     * to {@code getLeastActiveNode()} when the job has no label
     * requirements.
     */
    @Test
    public void findNodeForJobNoRequirementsFallsBack() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("nodes.initial", "2");
        p.setProperty("nodes.jobs.max", "4");
        p.setProperty("servers.total", "0");
        p.setProperty("group.thread.sleep", "10000");

        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        LabelledJob anyJob = new LabelledJob("any-node",
                Collections.emptyMap());

        Node result = group.findNodeForJob(anyJob);
        Assert.assertNotNull(
                "findNodeForJob should return a Node for jobs with no requirements",
                result);
    }

    /**
     * Verifies that a relay Node holds jobs in its queue without
     * executing them. The worker thread should not start, so jobs
     * remain available for the activity thread's relay loop.
     */
    @Test(timeout = 15000)
    public void relayNodeHoldsJobsWithoutExecuting() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = nodeGroupProperties();
        p.setProperty("nodes.labels.role", "relay");
        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        Node child = getChildNode(group);

        AtomicInteger runCount = new AtomicInteger(0);
        LabelledJob job = new LabelledJob("hold-test",
                Collections.singletonMap("platform", "macos")) {
            @Override
            public void run() {
                runCount.incrementAndGet();
                super.run();
            }
        };

        child.addJob(job);

        // Give time for the worker to start if it were going to
        Thread.sleep(2000);

        // Job should NOT have been executed
        Assert.assertEquals("Relay Node should not execute jobs",
                0, runCount.get());

        // Job should still be in the queue
        Assert.assertTrue("Job should remain in the relay Node's queue",
                child.toString().contains("1(4) jobs in queue"));
    }

    /**
     * Verifies that a Node with {@code role:relay} never executes
     * jobs, even when the job has no label requirements.
     */
    @Test
    public void relayNodeNeverSatisfiesRequirements() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = nodeGroupProperties();
        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        Node child = getChildNode(group);
        child.setLabel("role", "relay");

        // Job with no requirements — would normally run anywhere
        LabelledJob job = new LabelledJob("relay-test",
                Collections.emptyMap());

        Assert.assertFalse(
                "A relay Node should never satisfy any job requirements",
                child.satisfies(job.getRequiredLabels()));
    }

    /**
     * Verifies that {@link NodeGroup#findNodeForJob(Job)} skips relay
     * Nodes and returns null when the only child is a relay Node.
     */
    @Test
    public void findNodeForJobSkipsRelayNode() throws Exception {
        if (testProfileIs(TestUtils.PIPELINE)) return;

        Properties p = nodeGroupProperties();
        p.setProperty("nodes.labels.role", "relay");
        NodeGroup group = new NodeGroup(p, new NoOpFactory());

        LabelledJob job = new LabelledJob("relay-skip-test",
                Collections.emptyMap());

        Node result = group.findNodeForJob(job);
        Assert.assertNull(
                "findNodeForJob should not return a relay Node",
                result);
    }

    // ==================== Helpers ====================

    /**
     * Standard properties for a single-node NodeGroup.
     */
    private Properties nodeGroupProperties() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("nodes.initial", "1");
        p.setProperty("nodes.jobs.max", "4");
        p.setProperty("servers.total", "0");
        p.setProperty("group.thread.sleep", "10000");
        return p;
    }

    /**
     * Returns the first child Node from a NodeGroup.
     */
    private Node getChildNode(NodeGroup group) {
        List<Node> children = getChildNodes(group);
        Assert.assertFalse("NodeGroup should have at least one child Node",
                children.isEmpty());
        return children.get(0);
    }

    /**
     * Returns all child Nodes from a NodeGroup via reflection.
     */
    private List<Node> getChildNodes(NodeGroup group) {
        try {
            Field nodesField = NodeGroup.class.getDeclaredField("nodes");
            nodesField.setAccessible(true);
            Object raw = nodesField.get(group);
            List<Node> result = new ArrayList<>();
            for (Object item : (List<?>) raw) {
                result.add((Node) item);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to access NodeGroup.nodes", e);
        }
    }

    /**
     * A {@link Job} with configurable required labels that tracks execution.
     */
    static class LabelledJob implements Job {
        private final String taskId;
        private final Map<String, String> requiredLabels;
        private final CountDownLatch executedLatch;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        LabelledJob(String taskId, Map<String, String> requiredLabels) {
            this(taskId, requiredLabels, null);
        }

        LabelledJob(String taskId, Map<String, String> requiredLabels,
                     CountDownLatch executedLatch) {
            this.taskId = taskId;
            this.requiredLabels = requiredLabels != null
                    ? new HashMap<>(requiredLabels) : Collections.emptyMap();
            this.executedLatch = executedLatch;
        }

        @Override
        public void run() {
            if (executedLatch != null) {
                executedLatch.countDown();
            }
            future.complete(null);
        }

        @Override
        public Map<String, String> getRequiredLabels() {
            return requiredLabels;
        }

        @Override
        public String encode() {
            return "LabelledJob::" + taskId;
        }

        @Override
        public void set(String key, String value) { }

        @Override
        public String getTaskId() { return taskId; }

        @Override
        public String getTaskString() { return taskId; }

        @Override
        public CompletableFuture<Void> getCompletableFuture() { return future; }
    }

    /**
     * A no-op {@link JobFactory} that never produces jobs.
     */
    static class NoOpFactory implements JobFactory {
        @Override
        public Job nextJob() { return null; }

        @Override
        public Job createJob(String data) { return null; }

        @Override
        public String encode() { return "NoOpFactory"; }

        @Override
        public void set(String key, String value) { }

        @Override
        public boolean isComplete() { return false; }

        @Override
        public String getTaskId() { return "noop"; }

        @Override
        public String getName() { return "NoOp"; }

        @Override
        public double getCompleteness() { return 0; }

        @Override
        public void setPriority(double p) { }

        @Override
        public double getPriority() { return 1.0; }

        @Override
        public CompletableFuture<Void> getCompletableFuture() {
            return new CompletableFuture<>();
        }
    }
}
