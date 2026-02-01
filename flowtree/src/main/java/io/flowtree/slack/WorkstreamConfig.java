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

package io.flowtree.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Slack workstreams, loadable from YAML or JSON files.
 *
 * <p>Example YAML configuration:</p>
 * <pre>
 * workstreams:
 *   - channelId: "C0123456789"
 *     channelName: "#project-agent"
 *     agents:
 *       - host: "localhost"
 *         port: 7766
 *       - host: "localhost"
 *         port: 7767
 *     defaultBranch: "feature/work"
 *     pushToOrigin: true
 *     allowedTools: "Read,Edit,Write,Bash,Glob,Grep"
 *     maxTurns: 50
 *     maxBudgetUsd: 10.0
 * </pre>
 *
 * @author Michael Murray
 * @see SlackWorkstream
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkstreamConfig {

    private List<WorkstreamEntry> workstreams = new ArrayList<>();

    /**
     * Configuration entry for a single workstream.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkstreamEntry {
        private String channelId;
        private String channelName;
        private List<AgentEntry> agents = new ArrayList<>();
        private String defaultBranch;
        private boolean pushToOrigin = true;
        private String workingDirectory;
        private String allowedTools = "Read,Edit,Write,Bash,Glob,Grep";
        private int maxTurns = 50;
        private double maxBudgetUsd = 10.0;

        public String getChannelId() { return channelId; }
        public void setChannelId(String channelId) { this.channelId = channelId; }

        public String getChannelName() { return channelName; }
        public void setChannelName(String channelName) { this.channelName = channelName; }

        public List<AgentEntry> getAgents() { return agents; }
        public void setAgents(List<AgentEntry> agents) { this.agents = agents; }

        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

        public boolean isPushToOrigin() { return pushToOrigin; }
        public void setPushToOrigin(boolean pushToOrigin) { this.pushToOrigin = pushToOrigin; }

        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

        public String getAllowedTools() { return allowedTools; }
        public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

        public double getMaxBudgetUsd() { return maxBudgetUsd; }
        public void setMaxBudgetUsd(double maxBudgetUsd) { this.maxBudgetUsd = maxBudgetUsd; }

        /**
         * Converts this entry to a SlackWorkstream instance.
         */
        public SlackWorkstream toWorkstream() {
            SlackWorkstream ws = new SlackWorkstream(channelId, channelName);
            for (AgentEntry agent : agents) {
                ws.addAgent(agent.getHost(), agent.getPort());
            }
            ws.setDefaultBranch(defaultBranch);
            ws.setPushToOrigin(pushToOrigin);
            ws.setWorkingDirectory(workingDirectory);
            ws.setAllowedTools(allowedTools);
            ws.setMaxTurns(maxTurns);
            ws.setMaxBudgetUsd(maxBudgetUsd);
            return ws;
        }
    }

    /**
     * Configuration entry for an agent endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentEntry {
        private String host = "localhost";
        private int port = 7766;

        public AgentEntry() {}

        public AgentEntry(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public List<WorkstreamEntry> getWorkstreams() { return workstreams; }
    public void setWorkstreams(List<WorkstreamEntry> workstreams) { this.workstreams = workstreams; }

    /**
     * Loads configuration from a YAML file.
     *
     * @param file the YAML configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static WorkstreamConfig loadFromYaml(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(file, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a YAML input stream.
     *
     * @param inputStream the YAML input stream
     * @return the parsed configuration
     * @throws IOException if the stream cannot be read or parsed
     */
    public static WorkstreamConfig loadFromYaml(InputStream inputStream) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(inputStream, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a YAML string.
     *
     * @param yaml the YAML content
     * @return the parsed configuration
     * @throws IOException if the content cannot be parsed
     */
    public static WorkstreamConfig loadFromYamlString(String yaml) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(yaml, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a JSON file.
     *
     * @param file the JSON configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static WorkstreamConfig loadFromJson(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file, WorkstreamConfig.class);
    }

    /**
     * Loads configuration from a JSON string.
     *
     * @param json the JSON content
     * @return the parsed configuration
     * @throws IOException if the content cannot be parsed
     */
    public static WorkstreamConfig loadFromJsonString(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, WorkstreamConfig.class);
    }

    /**
     * Converts all entries to SlackWorkstream instances.
     *
     * @return list of workstreams
     */
    public List<SlackWorkstream> toWorkstreams() {
        List<SlackWorkstream> result = new ArrayList<>();
        for (WorkstreamEntry entry : workstreams) {
            result.add(entry.toWorkstream());
        }
        return result;
    }
}
