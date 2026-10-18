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

package io.flowtree.api;

import io.flowtree.Server;
import io.flowtree.job.JobFactory;
import io.flowtree.msg.NodeProxy;
import io.flowtree.node.NodeGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Server double for {@link FlowTreeApiEndpoint} submission tests: it reports one connected
 * peer, so the endpoint dispatches, and captures every queued factory instead of
 * distributing it, so a test can inspect the job the endpoint built.
 */
class QueueCapturingServer extends Server {
    /** Factories passed to {@link #addTask}, in submission order. */
    final List<JobFactory> queued = new ArrayList<>();
    /** Node group reporting a single (placeholder) peer. */
    private final NodeGroup group;

    /** Constructs the server without binding a listening socket (port 0). */
    QueueCapturingServer() throws IOException {
        super(ephemeralProperties());
        group = new NodeGroup(ephemeralProperties(), this) {
            @Override
            public NodeProxy[] getServers() {
                return new NodeProxy[1];
            }
        };
    }

    @Override
    public NodeGroup getNodeGroup() {
        return group;
    }

    @Override
    public boolean addTask(JobFactory task) {
        queued.add(task);
        return true;
    }

    /** Properties that skip server-socket binding and start no child nodes. */
    private static Properties ephemeralProperties() {
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("nodes.initial", "0");
        return p;
    }
}
