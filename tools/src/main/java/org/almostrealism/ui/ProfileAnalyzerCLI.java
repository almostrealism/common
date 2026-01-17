/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ui;

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Command-line interface for profile analysis with JSON output.
 *
 * <p>This tool provides JSON output for integration with the MCP profile analyzer server,
 * avoiding the memory issues of Python XML parsing for large profiles.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -cp ar-tools.jar org.almostrealism.ui.ProfileAnalyzerCLI &lt;command&gt; &lt;args...&gt;
 *
 * Commands:
 *   summary &lt;file&gt;              - Load profile and output summary as JSON
 *   slowest &lt;file&gt; [limit]      - Find slowest operations
 *   children &lt;file&gt; [node_key]  - List children of a node
 *   search &lt;file&gt; &lt;pattern&gt;     - Search operations by name pattern
 * </pre>
 */
public class ProfileAnalyzerCLI {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String filePath = args[1];

        try {
            switch (command) {
                case "summary":
                    printSummary(filePath);
                    break;
                case "slowest":
                    int limit = args.length > 2 ? Integer.parseInt(args[2]) : 10;
                    printSlowest(filePath, limit);
                    break;
                case "children":
                    String nodeKey = args.length > 2 ? args[2] : null;
                    printChildren(filePath, nodeKey);
                    break;
                case "search":
                    if (args.length < 3) {
                        System.err.println("Error: search requires a pattern argument");
                        System.exit(1);
                    }
                    searchOperations(filePath, args[2]);
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.out.println("{\"error\": \"" + escapeJson(e.getMessage()) + "\", " +
                    "\"type\": \"" + e.getClass().getSimpleName() + "\"}");
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: ProfileAnalyzerCLI <command> <file> [args...]");
        System.err.println("Commands:");
        System.err.println("  summary <file>              - Load profile and output summary");
        System.err.println("  slowest <file> [limit]      - Find slowest operations");
        System.err.println("  children <file> [node_key]  - List children of a node");
        System.err.println("  search <file> <pattern>     - Search operations by name");
    }

    private static void printSummary(String filePath) throws IOException {
        OperationProfileNode root = OperationProfileNode.load(filePath);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"name\": \"").append(escapeJson(root.getName())).append("\",\n");
        List<OperationProfileNode> allNodes = new ArrayList<>();
        collectNodes(root, allNodes);

        // Calculate total duration by summing all measured durations
        double totalDuration = allNodes.stream()
                .mapToDouble(ProfileAnalyzerCLI::getNodeDuration)
                .sum();

        json.append("  \"total_duration_seconds\": ").append(round(totalDuration, 6)).append(",\n");
        json.append("  \"total_duration_formatted\": \"").append(formatDuration(totalDuration)).append("\",\n");

        int nodeCount = allNodes.size();
        json.append("  \"node_count\": ").append(nodeCount).append(",\n");

        int compiledCount = countCompiledNodes(root);
        json.append("  \"compiled_operations\": ").append(compiledCount).append(",\n");

        List<OperationProfileNode> topOps = allNodes.stream()
                .filter(n -> getNodeDuration(n) > 0)
                .sorted(Comparator.comparingDouble(ProfileAnalyzerCLI::getNodeDuration).reversed())
                .limit(10)
                .collect(Collectors.toList());

        json.append("  \"top_operations\": [\n");
        for (int i = 0; i < topOps.size(); i++) {
            OperationProfileNode n = topOps.get(i);
            double nodeDuration = getNodeDuration(n);
            double pct = totalDuration > 0 ? (nodeDuration / totalDuration * 100) : 0;
            json.append("    {");
            json.append("\"key\": \"").append(escapeJson(n.getKey())).append("\", ");
            json.append("\"name\": \"").append(escapeJson(n.getName())).append("\", ");
            json.append("\"duration\": ").append(round(nodeDuration, 6)).append(", ");
            json.append("\"duration_formatted\": \"").append(formatDuration(nodeDuration)).append("\", ");
            json.append("\"percentage\": ").append(round(pct, 1));
            json.append("}");
            if (i < topOps.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        System.out.println(json);
    }

    private static void printSlowest(String filePath, int limit) throws IOException {
        OperationProfileNode root = OperationProfileNode.load(filePath);

        List<OperationProfileNode> allNodes = new ArrayList<>();
        collectNodes(root, allNodes);

        double totalDuration = allNodes.stream()
                .mapToDouble(ProfileAnalyzerCLI::getNodeDuration)
                .sum();

        List<OperationProfileNode> slowest = allNodes.stream()
                .filter(n -> getNodeDuration(n) > 0 && n.getKey() != null)
                .sorted(Comparator.comparingDouble(ProfileAnalyzerCLI::getNodeDuration).reversed())
                .limit(limit)
                .collect(Collectors.toList());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"total_profile_duration\": ").append(round(totalDuration, 4)).append(",\n");
        json.append("  \"slowest\": [\n");

        for (int i = 0; i < slowest.size(); i++) {
            OperationProfileNode n = slowest.get(i);
            double nodeDuration = getNodeDuration(n);
            double pct = totalDuration > 0 ? (nodeDuration / totalDuration * 100) : 0;

            json.append("    {");
            json.append("\"key\": \"").append(escapeJson(n.getKey())).append("\", ");
            json.append("\"name\": \"").append(escapeJson(n.getName())).append("\", ");
            json.append("\"duration\": ").append(round(nodeDuration, 6)).append(", ");
            json.append("\"duration_formatted\": \"").append(formatDuration(nodeDuration)).append("\", ");
            json.append("\"percentage\": ").append(round(pct, 1)).append(", ");

            Map<String, Integer> counts = n.getMetricCounts();
            if (counts != null && !counts.isEmpty()) {
                int invocations = counts.values().stream().mapToInt(Integer::intValue).sum();
                json.append("\"invocations\": ").append(invocations).append(", ");
                if (invocations > 0) {
                    json.append("\"avg_duration\": ").append(round(nodeDuration / invocations, 6)).append(", ");
                }
            }

            json.append("\"has_source\": ").append(hasSource(n));
            json.append("}");
            if (i < slowest.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        System.out.println(json);
    }

    private static void printChildren(String filePath, String nodeKey) throws IOException {
        OperationProfileNode root = OperationProfileNode.load(filePath);

        OperationProfileNode parent = nodeKey == null ? root : findByKey(root, nodeKey);
        if (parent == null) {
            System.out.println("{\"error\": \"Node not found: " + escapeJson(nodeKey) + "\"}");
            return;
        }

        List<OperationProfileNode> children = parent.getChildren() != null ?
                new ArrayList<>(parent.getChildren()) : new ArrayList<>();

        List<OperationProfileNode> sortedChildren = children.stream()
                .sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
                .limit(20)
                .collect(Collectors.toList());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"parent_key\": \"").append(escapeJson(parent.getKey())).append("\",\n");
        json.append("  \"parent_name\": \"").append(escapeJson(parent.getName())).append("\",\n");
        json.append("  \"total_children\": ").append(children.size()).append(",\n");
        json.append("  \"showing\": ").append(sortedChildren.size()).append(",\n");
        json.append("  \"children\": [\n");

        for (int i = 0; i < sortedChildren.size(); i++) {
            OperationProfileNode c = sortedChildren.get(i);
            double pct = parent.getTotalDuration() > 0 ?
                    (c.getTotalDuration() / parent.getTotalDuration() * 100) : 0;

            json.append("    {");
            json.append("\"key\": \"").append(escapeJson(c.getKey())).append("\", ");
            json.append("\"name\": \"").append(escapeJson(c.getName())).append("\", ");
            json.append("\"duration\": ").append(round(c.getTotalDuration(), 6)).append(", ");
            json.append("\"duration_formatted\": \"").append(formatDuration(c.getTotalDuration())).append("\", ");
            json.append("\"percentage\": ").append(round(pct, 1)).append(", ");
            json.append("\"has_children\": ").append(c.getChildren() != null && !c.getChildren().isEmpty()).append(", ");
            json.append("\"has_source\": ").append(hasSource(c));
            json.append("}");
            if (i < sortedChildren.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        System.out.println(json);
    }

    private static void searchOperations(String filePath, String pattern) throws IOException {
        OperationProfileNode root = OperationProfileNode.load(filePath);

        List<OperationProfileNode> allNodes = new ArrayList<>();
        collectNodes(root, allNodes);

        String lowerPattern = pattern.toLowerCase();

        List<OperationProfileNode> matches = allNodes.stream()
                .filter(n -> n.getName() != null && n.getName().toLowerCase().contains(lowerPattern))
                .sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
                .limit(20)
                .collect(Collectors.toList());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"pattern\": \"").append(escapeJson(pattern)).append("\",\n");
        json.append("  \"match_count\": ").append(matches.size()).append(",\n");
        json.append("  \"matches\": [\n");

        for (int i = 0; i < matches.size(); i++) {
            OperationProfileNode n = matches.get(i);
            json.append("    {");
            json.append("\"key\": \"").append(escapeJson(n.getKey())).append("\", ");
            json.append("\"name\": \"").append(escapeJson(n.getName())).append("\", ");
            json.append("\"duration\": ").append(round(n.getTotalDuration(), 6)).append(", ");
            json.append("\"duration_formatted\": \"").append(formatDuration(n.getTotalDuration())).append("\", ");
            json.append("\"has_source\": ").append(hasSource(n));
            json.append("}");
            if (i < matches.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}");

        System.out.println(json);
    }

    private static void collectNodes(OperationProfileNode node, List<OperationProfileNode> nodes) {
        nodes.add(node);
        if (node.getChildren() != null) {
            for (OperationProfileNode child : node.getChildren()) {
                collectNodes(child, nodes);
            }
        }
    }

    private static int countNodes(OperationProfileNode node) {
        int count = 1;
        if (node.getChildren() != null) {
            for (OperationProfileNode child : node.getChildren()) {
                count += countNodes(child);
            }
        }
        return count;
    }

    private static int countCompiledNodes(OperationProfileNode node) {
        int count = hasSource(node) ? 1 : 0;
        if (node.getChildren() != null) {
            for (OperationProfileNode child : node.getChildren()) {
                count += countCompiledNodes(child);
            }
        }
        return count;
    }

    private static OperationProfileNode findByKey(OperationProfileNode node, String key) {
        if (key.equals(node.getKey())) {
            return node;
        }
        if (node.getChildren() != null) {
            for (OperationProfileNode child : node.getChildren()) {
                OperationProfileNode found = findByKey(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasSource(OperationProfileNode node) {
        Map<String, List<OperationSource>> sources = node.getOperationSources();
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        List<OperationSource> sourceList = sources.get(node.getKey());
        return sourceList != null && !sourceList.isEmpty();
    }

    private static double getNodeDuration(OperationProfileNode node) {
        double measured = node.getMeasuredDuration();
        return measured > 0 ? measured : node.getSelfDuration();
    }

    private static String formatDuration(double seconds) {
        if (seconds < 0.001) {
            return String.format("%.1fus", seconds * 1000000);
        } else if (seconds < 1) {
            return String.format("%.2fms", seconds * 1000);
        } else {
            return String.format("%.3fs", seconds);
        }
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
