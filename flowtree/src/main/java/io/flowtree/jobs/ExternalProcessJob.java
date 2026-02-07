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

package io.flowtree.jobs;

import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
import org.almostrealism.util.KeyUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExternalProcessJob implements Job {
    public static final String COMMAND_SEPARATOR = ";;";
    public static final String JOB_SEPARATOR = "&;";

    private String taskId;
    private List<String> commands;

    private final CompletableFuture<Void> future = new CompletableFuture<>();

    public ExternalProcessJob() {
    }

    protected ExternalProcessJob(String taskId, String cmdString) {
        this.taskId = taskId;
        setCommandString(cmdString);
    }

    public ExternalProcessJob(String taskId, List<String> commands) {
        this.taskId = taskId;
        this.commands = commands;
    }

    @Override
    public String getTaskId() {
        return taskId;
    }

    @Override
    public String getTaskString() {
        return null;
    }

    public void setCommandString(String cmd) {
        this.commands = List.of(cmd.split(COMMAND_SEPARATOR));
    }

    public String getCommandString() {
        return String.join(COMMAND_SEPARATOR, commands);
    }

    @Override
    public CompletableFuture<Void> getCompletableFuture() {
        return future;
    }

    @Override
    public void run() {
        File cdir = new File("commands");
        if (!cdir.exists()) cdir.mkdir();

        String script = "commands/" + KeyUtils.generateKey() + ".sh";
        try (BufferedWriter out = new BufferedWriter(new FileWriter(script))) {
            out.write("#!/bin/sh\n");
            System.out.println("!/bin/sh");
            for (String cmd : commands) {
                out.write(cmd + "\n");
                System.out.println(cmd);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Process p = new ProcessBuilder().command("sh", script).inheritIO().start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        future.complete(null);
    }

    @Override
    public String encode() {
        return this.getClass().getName() +
                "::cmd:=" +
                Base64.getEncoder().encodeToString(getCommandString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void set(String k, String v) {
        if (k.equals("cmd")) {
            setCommandString(Optional.ofNullable(v).map(s -> new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)).orElse(null));
        } else {
            throw new IllegalArgumentException("");
        }
    }

    public static class Factory extends AbstractJobFactory {
        private List<String> commands;
        private int index;
        public Factory() {
            super(KeyUtils.generateKey());
        }

        public Factory(List<String>... command) {
            this();
            setCommands(command);
        }

        public void setCommands(List<String>... commands) {
            String code = String.join(JOB_SEPARATOR, Stream.of(commands).map(l -> String.join(COMMAND_SEPARATOR, l)).collect(Collectors.toList()));
            set("code", Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
        }

        public List<String> getCommands() {
            if (commands == null) {
                String code = new String(Base64.getDecoder().decode(get("code")), StandardCharsets.UTF_8);
                commands = List.of(code.split(JOB_SEPARATOR));
            }

            return commands;
        }

        @Override
        public Job nextJob() {
            if (index >= getCommands().size()) return null;
            return new ExternalProcessJob(getTaskId(), getCommands().get(index++));
        }

        @Override
        public double getCompleteness() {
            return index / (double) getCommands().size();
        }

        @Override
        public String toString() {
            return super.encode();
        }
    }
}
