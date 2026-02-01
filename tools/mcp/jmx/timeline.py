"""
Background memory sampler with trend analysis.

Provides a MemoryMonitor class that periodically polls jstat and writes
samples to a JSONL timeline file. Includes trend analysis via simple
linear regression for detecting memory leaks and estimating OOM risk.
"""

import json
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from jvm_diagnostics import is_process_alive, run_jstat, JVMDiagnosticsError


class MemoryMonitor:
    """Background daemon that samples JVM heap metrics at regular intervals.

    Writes JSON-lines to a timeline file in the run's jmx directory.
    Automatically stops when the target process exits.

    Args:
        pid: Target JVM process ID.
        run_id: Test run identifier (for logging/context).
        runs_dir: Path to the runs directory.
        interval_seconds: Seconds between samples (default: 5).
    """

    def __init__(self, pid: int, run_id: str, runs_dir: Path,
                 interval_seconds: int = 5):
        self.pid = pid
        self.run_id = run_id
        self.timeline_path = runs_dir / run_id / "jmx" / "timeline.jsonl"
        self.interval_seconds = interval_seconds
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._lock = threading.Lock()
        self.sample_count = 0
        self.error_count = 0
        self.last_error: Optional[str] = None

    def start(self) -> None:
        """Start the background monitoring thread."""
        if self._thread is not None and self._thread.is_alive():
            return

        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._monitor_loop,
            daemon=True,
            name=f"memory-monitor-{self.run_id}"
        )
        self._thread.start()

    def stop(self) -> None:
        """Signal the monitor to stop and wait for the thread to finish."""
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=self.interval_seconds + 2)

    @property
    def is_running(self) -> bool:
        """Whether the monitor thread is currently alive."""
        return self._thread is not None and self._thread.is_alive()

    def _monitor_loop(self) -> None:
        """Main monitoring loop. Polls jstat and writes samples."""
        # Ensure parent directory exists
        self.timeline_path.parent.mkdir(parents=True, exist_ok=True)

        while not self._stop_event.is_set():
            if not is_process_alive(self.pid):
                break

            try:
                gc_data = run_jstat(self.pid, "gc")
                sample = self._build_sample(gc_data)
                self._write_sample(sample)
                self.sample_count += 1
            except JVMDiagnosticsError as e:
                self.error_count += 1
                self.last_error = str(e)
                if "not running" in str(e).lower():
                    break
            except Exception as e:
                self.error_count += 1
                self.last_error = str(e)

            self._stop_event.wait(self.interval_seconds)

    def _build_sample(self, gc_data: dict) -> dict:
        """Build a timeline sample from jstat gc output.

        Computes aggregate heap usage from the individual pool columns.
        """
        # jstat -gc columns: S0C S1C S0U S1U EC EU OC OU MC MU ...
        # YGC YGCT FGC FGCT CGC CGCT GCT
        s0u = gc_data.get("S0U", 0.0)
        s1u = gc_data.get("S1U", 0.0)
        eu = gc_data.get("EU", 0.0)
        ou = gc_data.get("OU", 0.0)
        mu = gc_data.get("MU", 0.0)

        s0c = gc_data.get("S0C", 0.0)
        s1c = gc_data.get("S1C", 0.0)
        ec = gc_data.get("EC", 0.0)
        oc = gc_data.get("OC", 0.0)
        mc = gc_data.get("MC", 0.0)

        heap_used_kb = s0u + s1u + eu + ou
        heap_capacity_kb = s0c + s1c + ec + oc

        return {
            "timestamp": datetime.now().isoformat(),
            "heap_used_mb": round(heap_used_kb / 1024, 2),
            "heap_capacity_mb": round(heap_capacity_kb / 1024, 2),
            "old_gen_used_mb": round(ou / 1024, 2),
            "old_gen_capacity_mb": round(oc / 1024, 2),
            "metaspace_used_mb": round(mu / 1024, 2),
            "metaspace_capacity_mb": round(mc / 1024, 2),
            "young_gc_count": gc_data.get("YGC", 0),
            "young_gc_time_s": gc_data.get("YGCT", 0.0),
            "full_gc_count": gc_data.get("FGC", 0),
            "full_gc_time_s": gc_data.get("FGCT", 0.0),
            "total_gc_time_s": gc_data.get("GCT", 0.0)
        }

    def _write_sample(self, sample: dict) -> None:
        """Append a sample to the timeline JSONL file (thread-safe)."""
        with self._lock:
            with open(self.timeline_path, "a") as f:
                f.write(json.dumps(sample) + "\n")


def read_timeline(path: Path, last_n: Optional[int] = None) -> list[dict]:
    """Read timeline samples from a JSONL file.

    Args:
        path: Path to the timeline.jsonl file.
        last_n: If set, return only the last N samples.

    Returns:
        List of parsed sample dicts. Malformed lines are skipped.
    """
    if not path.exists():
        return []

    samples = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                samples.append(json.loads(line))
            except json.JSONDecodeError:
                continue

    if last_n is not None and last_n > 0:
        samples = samples[-last_n:]

    return samples


def compute_trend(samples: list[dict]) -> dict:
    """Compute memory trend metrics from timeline samples.

    Uses simple linear regression on heap_used_mb over time to estimate
    growth rate and potential OOM timing.

    Args:
        samples: List of timeline sample dicts (must have 'timestamp'
                 and 'heap_used_mb' fields).

    Returns:
        Dict with:
        - sample_count: Number of samples analyzed
        - duration_minutes: Time span of the samples
        - heap_growth_mb_per_min: Linear regression slope of heap usage
        - gc_frequency_per_min: GC events per minute (young + full)
        - estimated_oom_minutes: Minutes until heap capacity is reached
          at current growth rate (None if not growing or no capacity data)
        - current_heap_mb: Most recent heap_used_mb
        - current_capacity_mb: Most recent heap_capacity_mb
    """
    result = {
        "sample_count": len(samples),
        "duration_minutes": 0.0,
        "heap_growth_mb_per_min": 0.0,
        "gc_frequency_per_min": 0.0,
        "estimated_oom_minutes": None,
        "current_heap_mb": 0.0,
        "current_capacity_mb": 0.0
    }

    if len(samples) < 2:
        if samples:
            result["current_heap_mb"] = samples[-1].get("heap_used_mb", 0.0)
            result["current_capacity_mb"] = samples[-1].get("heap_capacity_mb", 0.0)
        return result

    # Parse timestamps to relative minutes
    try:
        t0 = datetime.fromisoformat(samples[0]["timestamp"])
        times_min = []
        heap_values = []
        for s in samples:
            t = datetime.fromisoformat(s["timestamp"])
            times_min.append((t - t0).total_seconds() / 60.0)
            heap_values.append(s.get("heap_used_mb", 0.0))
    except (KeyError, ValueError):
        return result

    duration = times_min[-1]
    result["duration_minutes"] = round(duration, 2)
    result["current_heap_mb"] = heap_values[-1]
    result["current_capacity_mb"] = samples[-1].get("heap_capacity_mb", 0.0)

    # Simple linear regression: y = mx + b
    n = len(times_min)
    sum_x = sum(times_min)
    sum_y = sum(heap_values)
    sum_xy = sum(x * y for x, y in zip(times_min, heap_values))
    sum_x2 = sum(x * x for x in times_min)

    denominator = n * sum_x2 - sum_x * sum_x
    if abs(denominator) < 1e-10:
        return result

    slope = (n * sum_xy - sum_x * sum_y) / denominator
    result["heap_growth_mb_per_min"] = round(slope, 4)

    # GC frequency
    if duration > 0:
        first_gc = (samples[0].get("young_gc_count", 0) +
                    samples[0].get("full_gc_count", 0))
        last_gc = (samples[-1].get("young_gc_count", 0) +
                   samples[-1].get("full_gc_count", 0))
        gc_events = last_gc - first_gc
        result["gc_frequency_per_min"] = round(gc_events / duration, 2)

    # Estimate OOM time
    if slope > 0 and result["current_capacity_mb"] > 0:
        remaining_mb = result["current_capacity_mb"] - result["current_heap_mb"]
        if remaining_mb > 0:
            result["estimated_oom_minutes"] = round(remaining_mb / slope, 1)

    return result
