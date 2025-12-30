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
import java.util.Properties;

public class ServerTest {
    @Test
    public void server() throws IOException, InterruptedException {
        Properties p = new Properties();
        p.setProperty("server.port", "7700");

        Server server = new Server(p);
        server.start();

        Thread.sleep(2 * 60 * 60 * 1000L);
    }

    @Test
    public void decodeJobTest() {
        String data = "io.flowtree.jobs.ExternalProcessJob::cmd:=c2xlZXA7MzA=";
        ExternalProcessJob j = (ExternalProcessJob) Server.instantiateJobClass(data);
        System.out.println(j.getCommandString());
    }
}
