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

import io.flowtree.fs.ResourceDistributionTask;
import io.flowtree.msg.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Periodically writes server status HTML files and uploads them to the
 * distributed file system on behalf of a {@link Server} instance.
 *
 * <p>The writer starts a daemon thread that sleeps for a configurable interval
 * then writes a status file, activity graph, sleep graph, and log file.  If a
 * {@link ResourceDistributionTask} is active the status HTML is also pushed
 * into the distributed file system so remote peers can read it.
 *
 * <p>Instances are package-private and created exclusively by {@link Server}.
 */
class ServerStatusWriter {

    /** The owning {@link Server} whose status is written. */
    private final Server server;

    /**
     * Constructs a new {@link ServerStatusWriter} for the given {@link Server}.
     *
     * @param server  The owning server instance.
     */
    ServerStatusWriter(Server server) {
        this.server = server;
    }

    /**
     * Starts a background thread that periodically writes a status HTML file and
     * activity/sleep graphs. Also starts the {@link io.flowtree.node.NodeGroup} activity monitor.
     *
     * @param file   Base path for status output files (e.g. {@code "/var/log/server"}).
     *               The HTML file will be written to {@code file + "-stat.html"}.
     * @param sleep  Interval between writes, in seconds.
     * @param r      Number of monitor samples taken per {@code sleep} interval.
     */
    void startWritingStatus(final String file, final int sleep, int r) {
        server.getNodeGroup().startMonitor(Server.MODERATE_PRIORITY, (1000 * sleep) / r);

        Thread t = new Thread(server.getThreadGroup(), new Runnable() {
            /**
             * Runs the status-write loop, sleeping between each write cycle.
             */
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(sleep * 1000L);

                        writeStatus(file);
                        server.getNodeGroup().storeActivityGraph(new File(file + ".ac"));
                        server.getNodeGroup().storeSleepGraph(new File(file + ".sl"));
                        server.getNodeGroup().writeLogFile(sleep / 60);
                    } catch (InterruptedException e) {
                        // Interrupted — continue loop.
                    } catch (IOException ioe) {
                        server.warn("IO error writing status file (" + ioe.getMessage() + ").");
                    }
                }
            }
        });

        t.setName("Status Output Thread");
        t.start();
    }

    /**
     * Writes the current server status as an HTML file and, if a
     * {@link ResourceDistributionTask} is active, also uploads the status to the
     * distributed file system.
     *
     * @param file   Base path for the output file. The HTML file is written to
     *               {@code file + "-stat.html"}.
     * @throws IOException  If writing the status file fails.
     */
    void writeStatus(String file) throws IOException {
        PrintStream p = new PrintStream(new FileOutputStream(file + "-stat.html"));
        server.printStatus(p);

        ResourceDistributionTask t = ResourceDistributionTask.getCurrentTask();

        if (t != null) {
            if (Message.verbose)
                server.log("Writing status to distributed file system...");

            int index = file.lastIndexOf("/");
            if (index >= 0) file = file.substring(index + 1);

            if (server.getHostname() != null)
                file = file + "-" + server.getHostname();

            long time = server.getStartTime() % 10000;

            OutputStream out = server.getOutputStream("/files/logs/" + file +
                    "-" + time + "-stat.html");
            p = new PrintStream(out);
            server.printStatus(p);
            out.close();
        }
    }
}
