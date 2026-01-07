# Profile Analyzer MCP Server

An MCP (Model Context Protocol) server that enables AI agents to explore and analyze performance profile data from Almost Realism computations.

## Overview

This server provides tools for:
- Loading and caching profile XML files
- Navigating hierarchical profile trees
- Examining timing metrics and invocation counts
- Retrieving generated kernel source code
- Finding performance bottlenecks
- Comparing profiles before/after optimizations

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
| `AR_PROFILE_CACHE_SIZE` | `10` | Maximum number of profiles to cache |

## Running the Server

```bash
python server.py
```

Or with custom configuration:

```bash
AR_PROFILE_DIR=/path/to/profiles AR_PROFILE_CACHE_SIZE=20 python server.py
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
- `profile_id`: Use this ID in subsequent calls
- `total_duration_seconds`: Overall execution time
- `node_count`: Number of operations in the profile
- `compiled_operations`: Operations with generated source code
- `top_operations`: Top 5 operations by time

### get_node_summary

Get detailed timing information for a specific node.

**Parameters:**
- `profile_id` (required): Profile ID from load_profile
- `node_key` (optional): Node key (uses root if omitted)

**Returns:**
- Timing breakdown (total, self, children)
- Invocation counts
- Stage breakdown (compile, run)
- Metadata

### list_children

List children of a node with timing information.

**Parameters:**
- `profile_id` (required): Profile ID
- `node_key` (optional): Parent node key
- `sort_by` (optional): `duration`, `name`, or `invocations`
- `limit` (optional): Maximum results (default: 20)

### get_source

Get generated kernel source code for an operation.

**Parameters:**
- `profile_id` (required): Profile ID
- `node_key` (required): Node key
- `format` (optional): `full` or `summary` (first 50 lines)

**Returns:**
- Source code with language detection (C, OpenCL, Metal)
- Argument information with keys and descriptions

### find_slowest

Find the N slowest operations in the profile.

**Parameters:**
- `profile_id` (required): Profile ID
- `limit` (optional): Number of results (default: 10)
- `min_duration` (optional): Minimum duration filter (seconds)
- `include_children` (optional): Include child time (default: false)

### search_operations

Search for operations by name pattern.

**Parameters:**
- `profile_id` (required): Profile ID
- `pattern` (required): Regex pattern to match
- `limit` (optional): Maximum results (default: 20)

### compare_profiles

Compare timing between two profiles.

**Parameters:**
- `profile_id_a` (required): First profile ID
- `profile_id_b` (required): Second profile ID
- `threshold` (optional): Minimum % change to report (default: 10)

**Returns:**
- Overall timing change
- List of operations with significant changes
- Status (improved/regressed) for each

## Usage Example

```
# 1. Find available profiles
list_profiles()

# 2. Load a profile
load_profile(path="utils/results/matmulLarge1.xml")
# Returns profile_id="abc123"

# 3. Find slowest operations
find_slowest(profile_id="abc123", limit=5)

# 4. Get details on a specific operation
get_node_summary(profile_id="abc123", node_key="45")

# 5. View generated source code
get_source(profile_id="abc123", node_key="45")

# 6. Compare with another run
load_profile(path="utils/results/matmulLarge2.xml")
# Returns profile_id="def456"

compare_profiles(profile_id_a="abc123", profile_id_b="def456")
```

## Profile XML Format

The server parses JavaBeans XML format produced by `OperationProfileNode.save()`. Key elements:

- `OperationProfileNode`: Tree structure with key, name, children
- `TimingMetric`: Entries (operation -> seconds) and counts
- `OperationSource`: Generated kernel code with arguments

## See Also

- `OperationProfileNode.java` - Java profile data structure
- `OperationProfileFX.java` - JavaFX UI for human users
