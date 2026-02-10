# MCP Tools Available to Claude Code

This document lists all MCP (Model Context Protocol) tools currently available.

---

## ar-test-runner

Tools for running and managing test executions.

| Tool | Description |
|------|-------------|
| `start_test_run` | Start a new test run asynchronously. Parameters: module, test_classes, test_methods, profile, depth, timeout_minutes, jvm_args, jmx_monitoring |
| `get_run_status` | Get the status of a test run including test counts and duration |
| `get_run_output` | Get console output from a test run with optional regex filtering |
| `get_run_failures` | Get failure information with truncated stacktraces |
| `list_runs` | List recent test runs, optionally filtered by status |
| `cancel_run` | Cancel a running test |

---

## ar-profile-analyzer

Tools for analyzing profile XML files.

| Tool | Description |
|------|-------------|
| `list_profiles` | List available profile XML files in a directory |
| `load_profile` | Load a profile XML file and return its summary |
| `find_slowest` | Find the N slowest operations in a profile |
| `list_children` | List children of a node with timing information |
| `search_operations` | Search for operations by name pattern |

---

## ar-jmx

Tools for JVM memory diagnostics via JDK diagnostic tools (jcmd, jstat, jfr).

| Tool | Description |
|------|-------------|
| `attach_to_run` | Verify connectivity to a forked test JVM |
| `get_heap_summary` | Get heap pool sizes, utilization, and GC totals from jstat |
| `get_gc_stats` | Get GC cause, utilization percentages, and timing |
| `get_class_histogram` | Get live object histogram from jcmd, with optional filtering and snapshots |
| `diff_class_histogram` | Compare two saved histogram snapshots and show per-class memory growth |
| `start_jfr_recording` | Start a Java Flight Recorder recording for allocation profiling |
| `stop_jfr_recording` | Dump and stop the active JFR recording |
| `get_allocation_report` | Aggregate allocation samples from a JFR recording |
| `get_thread_dump` | Get a thread dump from the forked test JVM |
| `get_native_memory` | Get Native Memory Tracking summary from jcmd |
| `start_memory_monitor` | Start background jstat sampling for trend analysis |
| `get_memory_timeline` | Read memory timeline samples and compute trend analysis |
| `attach_to_pid` | Attach to an arbitrary JVM process by PID |

---

## ar-docs

Tools for searching and reading Almost Realism documentation.

| Tool | Description |
|------|-------------|
| `search_ar_docs` | Search documentation for a keyword or phrase with fuzzy matching |
| `read_ar_module` | Read documentation for a specific module |
| `list_ar_modules` | List all available modules with brief descriptions |
| `read_quick_reference` | Get the condensed API quick reference |
| `read_ar_guidelines` | Read the CLAUDE.md development guidelines |
| `search_source_comments` | Search JavaDoc comments in source code |
| `read_internals` | Read implementation notes from the internals directory |

---

## ar-memory

Tools for persistent semantic memory storage and retrieval.

| Tool | Description |
|------|-------------|
| `memory_store` | Store a memory entry with semantic embedding for later retrieval |
| `memory_search` | Search memory entries by semantic similarity |
| `memory_delete` | Delete a memory entry by ID |
| `memory_list` | List memory entries, newest first, with optional tag filtering |

---

## ar-consultant

Documentation-aware assistant combining documentation search, semantic memory, and local LLM inference.

| Tool | Description |
|------|-------------|
| `consult` | Ask a question and get a documentation-grounded answer |
| `search_docs` | Search project documentation with a Consultant-generated summary |
| `recall` | Search memories and return Consultant-summarized results |
| `remember` | Store a memory after Consultant reformulation for terminology consistency |
| `start_consultation` | Begin a multi-turn consultation session |
| `continue_consultation` | Continue an existing consultation session |
| `end_consultation` | End a consultation session, optionally storing a summary as memory |
| `consultant_status` | Check the Consultant's status and backend configuration |
| `list_request_history` | List recent request history with optional filters |
| `export_request_history` | Export full request history records for offline analysis |

---

## ar-slack

Tools for sending messages to Slack.

| Tool | Description |
|------|-------------|
| `slack_send_message` | Send a message to a Slack channel |
| `slack_send_thread_reply` | Reply in a Slack thread |
