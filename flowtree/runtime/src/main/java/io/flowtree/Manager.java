/*
 * Copyright 2022 Michael Murray
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

import io.flowtree.jobs.ExternalProcessJob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Bootstrap entry point that reads a {@code control.sh} script, partitions it
 * into shell-command tasks at {@code #!} boundaries, and submits the resulting
 * {@link io.flowtree.jobs.ExternalProcessJob.Factory} to a FlowTree
 * {@link Server} for distributed execution.
 *
 * @author  Michael Murray
 */
public class Manager {
    /**
     * Reads {@code control.sh} from the working directory, builds an
     * {@link io.flowtree.jobs.ExternalProcessJob.Factory} from the contained
     * command groups, and starts a {@link Server} to distribute the work.
     *
     * @param args  command-line arguments (unused)
     * @throws IOException if {@code control.sh} cannot be read
     */
    public static void main(String[] args) throws IOException {
        List<String> commands = new ArrayList<>();
        List<List<String>> tasks = new ArrayList<>();

        Files.lines(Paths.get("control.sh")).forEach(l -> {
            if (l.startsWith("#!")) {
                if (!commands.isEmpty()) {
                    tasks.add(new ArrayList<>(commands));
                    commands.clear();
                }
            } else {
                commands.add(l);
            }
        });

        if (!commands.isEmpty()) {
            tasks.add(new ArrayList<>(commands));
        }

        ExternalProcessJob.Factory task = new ExternalProcessJob.Factory(tasks.toArray(new List[0]));
        task.setPriority(5.0);

        Properties p = new Properties();
        p.setProperty("nodes.peers.max", "100");
        p.setProperty("group.taskjobs", "5");
        p.setProperty("nodes.jobs.max", "2");
        p.setProperty("group.msc", "30");
        p.setProperty("server.status.file", "manager");
        p.setProperty("network.msg.verbose", "false");
        p.setProperty("network.msg.dverbose", "true");
        Server server = new Server(p);
        server.sendTask(task);
        server.start();
    }
}
