# AR Host Resource Monitor

Lightweight background monitor that continuously records host-level CPU
and memory usage. When a CI test times out, query the logs to see exactly
which processes were consuming resources at that moment.

## Prerequisites

- **jq**: `brew install jq` (macOS) or `apt-get install jq` (Linux)

## Quick Start

```bash
cd tools/ci/monitor

# 1. (Optional) Configure thresholds
cp .env.example .env
# Edit .env if you want to change defaults

# 2. Start the monitor
chmod +x ar-host-monitor.sh
./ar-host-monitor.sh

# 3. Query recent activity (in another terminal)
chmod +x ar-host-query.sh
./ar-host-query.sh --last 15m
```

## How It Works

`ar-host-monitor.sh` runs a sampling loop that every N seconds (default 10):

1. Runs `ps -eo pid,pcpu,pmem,rss,comm` to get process stats
2. Filters to processes above CPU or memory thresholds
3. Collects load averages from `uptime`
4. Writes one JSONL line per sample to a date-stamped log file
5. Cleans up log files older than the retention period on day rollover

Each sample is a single JSON line:

```json
{"ts":"2026-02-26T14:30:05Z","host":"mac-studio","load":[3.30,3.61,3.38],"procs":[{"pid":1256,"cpu":23.4,"mem":7.8,"rss_mb":10197,"cmd":"java"},{"pid":26449,"cpu":28.3,"mem":3.7,"rss_mb":4856,"cmd":"claude"}]}
```

Log files are named `YYYY-MM-DD.jsonl` and stored in the log directory
(default: `./logs`). At ~1.7 MB/day with typical process counts, storage
is negligible.

### Signal Handling

Pressing Ctrl+C (SIGINT) or sending SIGTERM triggers a graceful shutdown.
The PID file is removed automatically.

### Duplicate Prevention

A PID file (`.ar-host-monitor.pid`) prevents multiple instances from
running simultaneously. If a previous instance crashed, the stale PID
file is detected and cleaned up automatically.

## Querying Logs

`ar-host-query.sh` reads the JSONL logs and presents filtered results.

### Time Selection (pick one)

```bash
# Find samples closest to a specific timestamp
./ar-host-query.sh --at "2026-02-26T14:30:00"

# Show samples in a time range
./ar-host-query.sh --from "2026-02-26T14:00:00" --to "2026-02-26T15:00:00"

# Show last N minutes/hours
./ar-host-query.sh --last 15m
./ar-host-query.sh --last 1h
```

### Filters

```bash
# Filter by process name
./ar-host-query.sh --last 30m --proc java

# Only show high-load samples
./ar-host-query.sh --last 1h --min-load 8.0

# Filter by hostname (for centralized logs)
./ar-host-query.sh --last 1h --host mac-studio

# Combine filters
./ar-host-query.sh --last 2h --proc java --min-load 4.0
```

### Output Formats

By default, output is a human-readable table:

```
=== mac-studio at 2026-02-26T14:30:05Z (load: 3.30 3.61 3.38) ===
  PID       CPU%   MEM%   RSS_MB  CMD
  1256      23.4    7.8    10197  java
  26449     28.3    3.7     4856  claude
```

Use `--raw` for machine-readable JSONL output, suitable for piping to
other tools:

```bash
./ar-host-query.sh --last 15m --raw | jq '.procs[].cpu' | sort -rn
```

## Configuration

All configuration is via the `.env` file (see `.env.example`). Defaults
are sensible for most setups — configuration is optional.

| Variable | Default | Description |
|---|---|---|
| `MONITOR_INTERVAL` | `10` | Seconds between samples |
| `MONITOR_LOG_DIR` | `./logs` | JSONL output directory |
| `MONITOR_CPU_THRESHOLD` | `5.0` | Min %CPU to record a process |
| `MONITOR_MEM_THRESHOLD` | `2.0` | Min %MEM to record a process |
| `MONITOR_RETENTION_DAYS` | `14` | Days to keep log files |
| `HOSTNAME_LABEL` | `$(hostname -s)` | Label for this machine |

## Running in the Background

```bash
# Using nohup
nohup ./ar-host-monitor.sh > /dev/null 2>&1 &

# Or using tmux
tmux new-session -d -s ar-monitor './ar-host-monitor.sh'
```

### launchd Service (macOS Auto-Start)

To start the monitor automatically on login:

```bash
cat > ~/Library/LaunchAgents/com.almostrealism.host-monitor.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.almostrealism.host-monitor</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>-c</string>
        <string>REPLACE_WITH_FULL_PATH/tools/ci/monitor/ar-host-monitor.sh</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/ar-host-monitor.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/ar-host-monitor.log</string>
</dict>
</plist>
PLIST

# Load the service
launchctl load ~/Library/LaunchAgents/com.almostrealism.host-monitor.plist
```

### systemd Service (Linux Auto-Start)

```bash
cat > ~/.config/systemd/user/ar-host-monitor.service << 'UNIT'
[Unit]
Description=AR Host Resource Monitor

[Service]
ExecStart=REPLACE_WITH_FULL_PATH/tools/ci/monitor/ar-host-monitor.sh
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable --now ar-host-monitor
```

## Typical Workflow

When a CI test times out:

1. Note the timestamp from the CI log
2. Query: `./ar-host-query.sh --at "2026-02-26T14:30:00"`
3. See which processes were consuming CPU/memory at that moment
4. Correlate with the timeout to determine if resource contention was the cause

## Files

```
tools/ci/monitor/
├── .env.example         # Configuration template
├── ar-host-monitor.sh   # Background sampling daemon
├── ar-host-query.sh     # Retroactive query tool
└── README.md            # This file
```
