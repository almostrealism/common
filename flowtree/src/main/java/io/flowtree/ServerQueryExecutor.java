/*
 * Copyright 2018 Michael Murray
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

package io.flowtree;

import io.almostrealism.db.Query;
import io.flowtree.fs.OutputServer;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;

import java.io.IOException;
import java.util.Map;

/**
 * Executes distributed database queries on behalf of a {@link Server} instance.
 *
 * <p>A query is first run against the local {@link OutputServer}'s database
 * connection; if the query carries a positive relay count the query is then
 * forwarded to each connected peer (excluding the originating peer), and the
 * results are concatenated using {@link Query#sep} as the delimiter.
 *
 * <p>Instances are package-private and created exclusively by {@link Server}.
 */
class ServerQueryExecutor {

    /** The owning {@link Server} whose peer list and logging facilities are used. */
    private final Server server;

    /**
     * Constructs a new {@link ServerQueryExecutor} for the given {@link Server}.
     *
     * @param server  The owning server instance.
     */
    ServerQueryExecutor(Server server) {
        this.server = server;
    }

    /**
     * Executes the given {@link Query} locally and, if the query's relay count is
     * greater than zero, relays it to all connected peers. Uses the default query timeout.
     *
     * @param q  The query to execute.
     * @return  A {@link Message} containing the aggregated query result string.
     * @throws IOException  If an IO error occurs while relaying the query.
     */
    Message executeQuery(Query q) throws IOException {
        return executeQuery(q, NodeProxy.queryTimeout);
    }

    /**
     * Executes the given {@link Query} locally and, if the query's relay count is
     * greater than zero, relays it to all connected peers using the specified timeout.
     *
     * @param q        The query to execute.
     * @param timeout  Maximum time in milliseconds to wait for each peer's response.
     * @return  A {@link Message} containing the aggregated query result string.
     * @throws IOException  If an IO error occurs while relaying the query.
     */
    Message executeQuery(Query q, long timeout) throws IOException {
        return executeQuery(q, null, timeout);
    }

    /**
     * Executes the given {@link Query} against the local output server and optionally
     * relays it to all connected peers except the one that originated the query.
     *
     * @param q        The query to execute.
     * @param p        The peer {@link NodeProxy} that sent the query (excluded from relay),
     *                 or {@code null} to relay to all peers.
     * @param timeout  Maximum time in milliseconds to wait for each peer's response.
     * @return  A {@link Message} containing the aggregated query result string.
     * @throws IOException  If an IO error occurs while relaying the query.
     */
    Message executeQuery(Query q, NodeProxy p, long timeout) throws IOException {
        OutputServer dbs = OutputServer.getCurrentServer();

        StringBuilder result = new StringBuilder();

        if (dbs != null) {
            if (Message.verbose)
                server.log("Executing " + q);

            Map h = dbs.getDatabaseConnection().executeQuery(q);

            if (Message.verbose)
                server.log("Received " + h.size() + " elements from query.");

            result.append(Query.toString(h));

            if (Message.verbose)
                server.log("Query result contains " + result.length() + " characters.");
        }

        if (q.getRelay() > 0) {
            if (Message.verbose)
                server.log("Relaying Query...");

            q.deincrementRelay();

            NodeProxy[] peers = server.getNodeGroup().getServers();

            i: for (int i = 0; i < peers.length; i++) {
                if (peers[i] == p) continue i;

                if (Message.verbose)
                    server.log("Writing " + q);

                peers[i].writeObject(q, -1);
                Message m = (Message) peers[i].waitForMessage(Message.StringMessage, null, timeout);

                if (Message.verbose)
                    server.log("Received " + m + " after waiting " + timeout + " msecs.");

                if (m != null && m.getData() != null && m.getData().length() > 0) {
                    if (result.length() > 0) result.append(Query.sep);
                    result.append(m.getData());
                }
            }
        }

        Message m = new Message(Message.StringMessage, -1);
        m.setString(result.toString());
        return m;
    }
}
