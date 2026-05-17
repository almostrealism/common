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
 * Shared base for node-relay simulation tests, providing scenario infrastructure
 * and inner job types used by all simulation suites.
 *
 * @author Michael Murray
 */
public abstract class NodeRelaySimulationBase extends ServerTestBase {

    /**
     * JVM-global latch registry shared across all {@link Server} instances in the
     * same test JVM. {@link TrackingJob} looks up its latch by task ID when
     * executed, regardless of which Server thread runs it.
     */
    public static final ConcurrentHashMap<String, CountDownLatch> LATCHES =
            new ConcurrentHashMap<>();

    /** Port counter shared across all simulation test classes. */
    protected static final AtomicInteger NEXT_PORT = new AtomicInteger(19100);

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
    protected boolean runScenario(String id, Properties controllerP,
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
    protected Properties controllerProps(int port) {
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
    protected Properties agentProps(int port, int controllerPort, int nodeCount,
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
    protected List<Properties> agentPropsList(Properties... props) {
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
    protected void speedUpNodes(Server s) throws ReflectiveOperationException {
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
    protected void speedUpNode(Node n) throws ReflectiveOperationException {
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
     * io.flowtree.test.NodeRelaySimulationBase$TrackingJob::id:=&lt;taskId&gt;[::label.&lt;key&gt;:=&lt;value&gt;...]
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
            sb.append("io.flowtree.test.NodeRelaySimulationBase$TrackingJob");
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
            return "io.flowtree.test.NodeRelaySimulationBase$TrackingJobFactory::id:=" + taskId;
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
