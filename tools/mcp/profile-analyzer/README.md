# Profile Analyzer MCP Server

An MCP (Model Context Protocol) server that enables AI agents to explore and analyze performance profile data from Almost Realism computations.

## Overview

This server provides tools for:
- Loading and caching profile XML files
- Navigating hierarchical profile trees
- Examining timing metrics and invocation counts
- Finding performance bottlenecks
- Analyzing compile vs run time breakdown

## Installation

```bash
cd tools/mcp/profile-analyzer
pip install -r requirements.txt
```

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_PROFILE_DIR` | `utils/results` | Default directory for profile files |
| `AR_TOOLS_DIR` | `/workspace/project/common/tools` | Path to tools module |

## Running the Server

```bash
python server.py
```

Or with custom configuration:

```bash
AR_PROFILE_DIR=/path/to/profiles python server.py
```

## Claude Desktop Configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "ar-profile-analyzer": {
      "command": "python",
      "args": ["/path/to/tools/mcp/profile-analyzer/server.py"],
      "env": {
        "AR_PROFILE_DIR": "/path/to/utils/results"
      }
    }
  }
}
```

## Tools

### list_profiles

List available profile XML files in a directory.

**Parameters:**
- `directory` (optional): Directory to search (default: `utils/results`)
- `pattern` (optional): Glob pattern (default: `*.xml`)

**Example:**
```
list_profiles(directory="utils/results", pattern="*.xml")
```

### load_profile

Load a profile and return its summary with top operations.

**Parameters:**
- `path` (required): Path to the profile XML file

**Returns:**
- `name`: Root node name
- `total_duration_seconds`: Overall execution time
- `node_count`: Number of operations in the profile
- `compiled_operations`: Operations with generated source code
- `top_operations`: Top 10 operations by time

### find_slowest

Find the N slowest operations in the profile.

**Parameters:**
- `path` (required): Path to the profile XML file
- `limit` (optional): Number of results (default: 10)

**Returns:**
- List of operations sorted by duration, with percentage of total time

### list_children

List children of a node with timing information.

**Parameters:**
- `path` (required): Path to the profile XML file
- `node_key` (optional): Parent node key (uses root if omitted)

**Returns:**
- Parent info and list of children sorted by duration

### search_operations

Search for operations by name pattern.

**Parameters:**
- `path` (required): Path to the profile XML file
- `pattern` (required): Pattern to match operation names (case-insensitive substring match)

**Returns:**
- Matching operations sorted by duration

### get_timing_breakdown

Get compile vs run time breakdown for an operation.

**Parameters:**
- `path` (required): Path to the profile XML file
- `node_key` (required): Node key to analyze

**Returns:**
- `compile_time`: Total time spent compiling (seconds)
- `run_time`: Total time spent executing (seconds)
- `compile_count`: Number of compilations
- `run_count`: Number of executions
- `stage_details`: Compilation stage breakdown if available

### find_slowest_by_category

Find slowest operations filtered by timing category.

**Parameters:**
- `path` (required): Path to the profile XML file
- `category` (optional): `"compile"`, `"run"`, or `"all"` (default: `"all"`)
- `limit` (optional): Maximum results (default: 10)

**Returns:**
- Operations sorted by the specified category's duration

## Usage Example

```
# 1. Find available profiles
list_profiles()

# 2. Load a profile
load_profile(path="utils/results/my_profile.xml")

# 3. Find slowest operations
find_slowest(path="utils/results/my_profile.xml", limit=5)

# 4. Get compile vs run breakdown for a specific operation
get_timing_breakdown(path="utils/results/my_profile.xml", node_key="1047")

# 5. Find slowest compilation operations
find_slowest_by_category(path="utils/results/my_profile.xml", category="compile", limit=5)
```

## Profile XML Format

The server parses JavaBeans XML format produced by `OperationProfileNode.save()`. Key elements:

- `OperationProfileNode`: Tree structure with key, name, children
- `TimingMetric`: Entries (operation -> seconds) and counts
- `OperationSource`: Generated kernel code with arguments

### Timing Entry Suffixes

Profile entries use suffixes to distinguish timing categories:

| Suffix | Meaning |
|--------|---------|
| `" compile"` | Code generation + native compilation time |
| `" run"` | Kernel execution time |

Example: `"f_assignment_1047 compile"` and `"f_assignment_1047 run"` track the compile and run times for operation 1047 separately.

## Architecture Notes

This MCP server uses a stateless design - each tool call specifies the profile path directly rather than using profile IDs. The Java `ProfileAnalyzerCLI` handles XML parsing to avoid memory issues with large profiles in Python.

## Planned Features

The following features are planned but not yet implemented:

- `get_source`: Retrieve generated kernel source code for an operation
- `compare_profiles`: Compare timing between two profile files

## See Also

- [Profiling Guide](../../../docs/internals/profiling.md) - Full profiling documentation
- `OperationProfileNode.java` - Java profile data structure
- `OperationProfileFX.java` - JavaFX UI for human users
- `ProfileAnalyzerCLI.java` - Java CLI for JSON output
