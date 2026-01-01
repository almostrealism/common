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

package io.flowtree.test;

import io.flowtree.Server;
import io.flowtree.jobs.ExternalProcessJob;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.stream.IntStream;

public class SubmitJobTest {
    @Test
    public void submitProcess() throws IOException {
        Properties p = new Properties();
        p.setProperty("server.port", "7701");
        p.setProperty("nodes.initial", "0");
        p.setProperty("servers.total", "1");
        p.setProperty("servers.0.host", "localhost");
        p.setProperty("servers.0.port", "7766");

        ArrayList<String> commands = new ArrayList<>();
        IntStream.range(0, 6).forEach(i -> {
            commands.add("sleep 2m");
            if (i % 3 == 0) commands.add("echo \"Hello World\"");
            commands.add("sleep " + (int) (Math.random() * 3) + "m");
        });

        Server server = new Server(p);
        IntStream.range(0, 10).forEach(i ->
                server.sendTask(new ExternalProcessJob.Factory(commands, commands, commands, commands, commands, commands, commands, commands), 0));
    }
}
