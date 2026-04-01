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
import org.almostrealism.io.ConsoleFeatures;
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

/**
 * A {@link Job} implementation that runs one or more shell commands as an
 * external process in the FlowTree worker environment.
 *
 * <p>Each job instance holds a list of shell commands. When executed, the
 * commands are written to a uniquely named shell script under a {@code commands/}
 * subdirectory and then run via {@code sh}. This approach ensures that
 * multi-line commands and shell constructs are preserved faithfully.</p>
 *
 * <p>The {@link Factory} inner class supports batching: a single factory
 * holds multiple command sets (each a {@link List}&lt;String&gt;), and
 * emits one {@code ExternalProcessJob} per set via successive
 * {@link Factory#nextJob()} calls.</p>
 *
 * <h2>Encoding</h2>
 * <p>Commands are Base64-encoded during serialization to safely transport
 * arbitrary shell syntax through the FlowTree messaging layer.</p>
 *
 * @author Michael Murray
 * @see io.flowtree.job.Job
 */
public class ExternalProcessJob implements Job, ConsoleFeatures {

    /**
     * Delimiter used to separate individual commands within a single job's
     * command string during encoding and decoding.
     */
    public static final String COMMAND_SEPARATOR = ";;";

    /**
     * Delimiter used by the {@link Factory} to separate multiple job command
     * sets within the {@code code} property.
     */
    public static final String JOB_SEPARATOR = "&;";

    /** Unique identifier for the task this job belongs to. */
    private String taskId;

    /** Ordered list of shell commands to execute in the generated script. */
    private List<String> commands;

    /** Future completed after all commands finish (or on error). */
    private final CompletableFuture<Void> future = new CompletableFuture<>();

    /**
     * No-argument constructor required for reflective deserialization.
     */
    public ExternalProcessJob() {
    }

    /**
     * Creates an {@code ExternalProcessJob} from an encoded command string.
     *
     * <p>The command string is split on {@link #COMMAND_SEPARATOR} to
     * produce the list of individual commands to run.</p>
     *
     * @param taskId    the task identifier
     * @param cmdString the encoded command string (commands joined by
     *                  {@link #COMMAND_SEPARATOR})
     */
    protected ExternalProcessJob(String taskId, String cmdString) {
        this.taskId = taskId;
        setCommandString(cmdString);
    }

    /**
     * Creates an {@code ExternalProcessJob} from an explicit command list.
     *
     * @param taskId   the task identifier
     * @param commands the ordered list of shell commands to execute
     */
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

    /**
     * Sets the command list by splitting an encoded command string on
     * {@link #COMMAND_SEPARATOR}.
     *
     * @param cmd the encoded command string
     */
    public void setCommandString(String cmd) {
        this.commands = List.of(cmd.split(COMMAND_SEPARATOR));
    }

    /**
     * Returns the commands joined by {@link #COMMAND_SEPARATOR}.
     *
     * @return the encoded command string
     */
    public String getCommandString() {
        return String.join(COMMAND_SEPARATOR, commands);
    }

    @Override
    public CompletableFuture<Void> getCompletableFuture() {
        return future;
    }

    /**
     * Executes all commands by writing them to a temporary shell script and
     * running it via {@code sh}.
     *
     * <p>The script is created under a {@code commands/} directory relative
     * to the JVM working directory. Its filename is a randomly generated key.
     * Standard I/O is inherited from the parent process so that output
     * appears in the worker log. The {@link #getCompletableFuture() future}
     * is completed after the process exits.</p>
     */
    @Override
    public void run() {
        File cdir = new File("commands");
        if (!cdir.exists()) cdir.mkdir();

        String script = "commands/" + KeyUtils.generateKey() + ".sh";
        try (BufferedWriter out = new BufferedWriter(new FileWriter(script))) {
            out.write("#!/bin/sh\n");
            log("!/bin/sh");
            for (String cmd : commands) {
                out.write(cmd + "\n");
                log(cmd);
            }
        } catch (IOException e) {
            warn("Failed to write script: " + e.getMessage(), e);
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder().command("sh", script);
            pb.inheritIO();
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }

        future.complete(null);
    }

    /**
     * Encodes this job as a class-name-prefixed key-value string suitable for
     * transmission through the FlowTree messaging layer.
     *
     * <p>The command list is joined by {@link #COMMAND_SEPARATOR}, then
     * Base64-encoded to safely transport arbitrary shell syntax.</p>
     *
     * @return the encoded job string
     */
    @Override
    public String encode() {
        return this.getClass().getName() +
                "::cmd:=" +
                Base64.getEncoder().encodeToString(getCommandString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Applies a deserialized key-value property to this job.
     *
     * <p>Only the {@code "cmd"} key is recognized. Its value is expected to be
     * Base64-encoded; it is decoded and split on {@link #COMMAND_SEPARATOR} to
     * restore the command list.</p>
     *
     * @param k the property key
     * @param v the property value (Base64-encoded command string for {@code "cmd"})
     * @throws IllegalArgumentException if {@code k} is not {@code "cmd"}
     */
    @Override
    public void set(String k, String v) {
        if (k.equals("cmd")) {
            setCommandString(Optional.ofNullable(v).map(s -> new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)).orElse(null));
        } else {
            throw new IllegalArgumentException("");
        }
    }

    /**
     * Factory that produces a sequence of {@link ExternalProcessJob} instances,
     * one per command set.
     *
     * <p>Multiple command sets are encoded together in the factory's {@code code}
     * property (joined by {@link #JOB_SEPARATOR}), allowing a single factory
     * to schedule several distinct shell script executions as separate jobs.
     * {@link #nextJob()} returns {@code null} once all command sets have been
     * dispatched, signalling completion to the FlowTree scheduler.</p>
     */
    public static class Factory extends AbstractJobFactory {

        /** Decoded command sets, lazily loaded from the {@code code} property. */
        private List<String> commands;

        /** Index of the next command set to emit via {@link #nextJob()}. */
        private int index;

        /**
         * Creates a factory with a randomly generated task ID.
         */
        public Factory() {
            super(KeyUtils.generateKey());
        }

        /**
         * Creates a factory pre-populated with one or more command sets.
         *
         * @param command the command sets; each {@link List}&lt;String&gt; becomes
         *                one {@link ExternalProcessJob}
         */
        public Factory(List<String>... command) {
            this();
            setCommands(command);
        }

        /**
         * Encodes and stores multiple command sets into the {@code code}
         * property for later retrieval by {@link #getCommands()}.
         *
         * <p>Within each set, commands are joined by {@link #COMMAND_SEPARATOR}.
         * Sets are joined by {@link #JOB_SEPARATOR}. The combined string is
         * Base64-encoded before storage.</p>
         *
         * @param commands the command sets to store
         */
        public void setCommands(List<String>... commands) {
            String code = String.join(JOB_SEPARATOR, Stream.of(commands).map(l -> String.join(COMMAND_SEPARATOR, l)).collect(Collectors.toList()));
            set("code", Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
        }

        /**
         * Returns all command sets decoded from the {@code code} property.
         *
         * <p>The result is cached after the first call. Each element is the
         * raw encoded string for one job (commands joined by
         * {@link #COMMAND_SEPARATOR}).</p>
         *
         * @return list of encoded command-set strings
         */
        public List<String> getCommands() {
            if (commands == null) {
                String code = new String(Base64.getDecoder().decode(get("code")), StandardCharsets.UTF_8);
                commands = List.of(code.split(JOB_SEPARATOR));
            }

            return commands;
        }

        /**
         * Returns the next {@link ExternalProcessJob} to run, or {@code null}
         * when all command sets have been dispatched.
         *
         * @return the next job, or {@code null} if exhausted
         */
        @Override
        public Job nextJob() {
            if (index >= getCommands().size()) return null;
            return new ExternalProcessJob(getTaskId(), getCommands().get(index++));
        }

        /**
         * Returns the fraction of command sets that have been dispatched.
         *
         * @return a value in {@code [0.0, 1.0]} representing dispatch progress
         */
        @Override
        public double getCompleteness() {
            return index / (double) getCommands().size();
        }

        /**
         * Returns the encoded factory string for transmission over the
         * FlowTree messaging layer.
         *
         * @return the encoded factory string
         */
        @Override
        public String toString() {
            return super.encode();
        }
    }
}
