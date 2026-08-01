package org.almostrealism.heap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.almostrealism.io.Console;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.HeapSummary;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline HPROF heap dump analyzer.
 *
 * <p>Parses binary {@code .hprof} files using the NetBeans Profiler heap walker
 * library and outputs structured JSON to stdout. Designed to be invoked as a
 * subprocess by the ar-jmx MCP server.</p>
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code histogram} &mdash; class histogram by instance count and shallow size</li>
 *   <li>{@code dominators} &mdash; dominator tree summary by retained size</li>
 *   <li>{@code summary} &mdash; both views plus heap metadata</li>
 * </ul>
 */
public class HeapAnalyzer {

	/** Shared Jackson mapper configured for indented JSON output. */
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT);
	/** Default number of top entries returned when {@code --top} is not specified. */
	private static final int DEFAULT_TOP = 30;

	/**
	 * Entry point. Expects: {@code <command> [--top N] <file.hprof>}.
	 */
	public static void main(String[] args) {
		if (args.length < 2) {
			printUsageAndExit();
		}

		String command = args[0];
		int top = DEFAULT_TOP;
		String filePath = null;

		for (int i = 1; i < args.length; i++) {
			if ("--top".equals(args[i]) && i + 1 < args.length) {
				try {
					top = Integer.parseInt(args[i + 1]);
				} catch (NumberFormatException e) {
					printError("Invalid --top value: " + args[i + 1]);
					return;
				}
				i++;
			} else {
				filePath = args[i];
			}
		}

		if (filePath == null) {
			printError("No HPROF file specified");
			return;
		}

		File hprofFile = new File(filePath);
		if (!hprofFile.exists()) {
			printError("File not found: " + filePath);
			return;
		}

		if (!hprofFile.canRead()) {
			printError("Cannot read file: " + filePath);
			return;
		}

		try {
			Heap heap = HeapFactory.createHeap(hprofFile);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("file", hprofFile.getAbsolutePath());
			result.put("top", top);

			switch (command) {
				case "histogram":
					result.put("command", "histogram");
					result.put("histogram", buildHistogram(heap, top));
					break;
				case "dominators":
					result.put("command", "dominators");
					result.put("dominators", buildDominators(heap, top));
					break;
				case "summary":
					result.put("command", "summary");
					result.put("metadata", buildMetadata(heap));
					result.put("histogram", buildHistogram(heap, top));
					result.put("dominators", buildDominators(heap, top));
					break;
				default:
					printError("Unknown command: " + command + ". Use histogram, dominators, or summary.");
					return;
			}

			Console.root().println(MAPPER.writeValueAsString(result));
		} catch (IOException e) {
			printError("Failed to parse HPROF file: " + e.getMessage());
		}
	}

	/**
	 * Build a class histogram sorted by shallow size descending.
	 *
	 * @param heap the parsed heap
	 * @param top  maximum number of classes to include
	 * @return list of histogram entry maps
	 */
	static List<Map<String, Object>> buildHistogram(Heap heap, int top) {
		List<JavaClass> allClasses = heap.getAllClasses();
		List<ClassStats> statsList = new ArrayList<>(allClasses.size());

		long totalInstances = 0;
		long totalBytes = 0;

		for (JavaClass javaClass : allClasses) {
			int instanceCount = javaClass.getInstancesCount();
			long shallowSize = javaClass.getAllInstancesSize();
			if (instanceCount > 0) {
				statsList.add(new ClassStats(javaClass.getName(), instanceCount, shallowSize));
				totalInstances += instanceCount;
				totalBytes += shallowSize;
			}
		}

		statsList.sort(Comparator.comparingLong(ClassStats::getShallowSize).reversed());

		List<Map<String, Object>> entries = new ArrayList<>();
		int rank = 1;
		for (ClassStats stats : statsList) {
			if (rank > top) break;

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("rank", rank);
			entry.put("class_name", stats.getClassName());
			entry.put("instances", stats.getInstanceCount());
			entry.put("shallow_bytes", stats.getShallowSize());
			entry.put("shallow_mb", round(stats.getShallowSize() / (1024.0 * 1024.0)));
			entries.add(entry);
			rank++;
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("total_classes", statsList.size());
		summary.put("total_instances", totalInstances);
		summary.put("total_bytes", totalBytes);
		summary.put("total_mb", round(totalBytes / (1024.0 * 1024.0)));

		List<Map<String, Object>> result = new ArrayList<>();
		Map<String, Object> wrapper = new LinkedHashMap<>();
		wrapper.put("summary", summary);
		wrapper.put("classes", entries);
		result.add(wrapper);
		return result;
	}

	/**
	 * Build a dominator tree summary sorted by retained size descending.
	 *
	 * <p>Uses {@link Heap#getBiggestObjectsByRetainedSize(int)} from the
	 * NetBeans Profiler, which computes the dominator tree internally.
	 * This can be slow on very large heaps since it requires a full
	 * graph traversal.</p>
	 *
	 * @param heap the parsed heap
	 * @param top  maximum number of dominators to include
	 * @return list of dominator entry maps
	 */
	static List<Map<String, Object>> buildDominators(Heap heap, int top) {
		List<Instance> dominators;
		try {
			List<?> raw = heap.getBiggestObjectsByRetainedSize(top);
			dominators = new ArrayList<>(raw.size());
			for (Object obj : raw) {
				dominators.add((Instance) obj);
			}
		} catch (Exception e) {
			List<Map<String, Object>> errorResult = new ArrayList<>();
			Map<String, Object> errorEntry = new LinkedHashMap<>();
			errorEntry.put("error", "Dominator computation failed: " + e.getMessage());
			errorResult.add(errorEntry);
			return errorResult;
		}

		List<Map<String, Object>> entries = new ArrayList<>();
		int rank = 1;
		for (Instance instance : dominators) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("rank", rank);
			entry.put("class_name", instance.getJavaClass().getName());
			entry.put("instance_id", instance.getInstanceId());
			entry.put("retained_bytes", instance.getRetainedSize());
			entry.put("retained_mb", round(instance.getRetainedSize() / (1024.0 * 1024.0)));
			entry.put("shallow_bytes", instance.getSize());
			entries.add(entry);
			rank++;
		}

		return entries;
	}

	/**
	 * Build heap metadata (total size, class count, instance count).
	 *
	 * @param heap the parsed heap
	 * @return metadata map
	 */
	static Map<String, Object> buildMetadata(Heap heap) {
		Map<String, Object> metadata = new LinkedHashMap<>();

		HeapSummary summary = heap.getSummary();
		metadata.put("total_classes", heap.getAllClasses().size());
		metadata.put("total_live_instances", summary.getTotalLiveInstances());
		metadata.put("total_live_bytes", summary.getTotalLiveBytes());
		metadata.put("total_live_mb", round(summary.getTotalLiveBytes() / (1024.0 * 1024.0)));
		metadata.put("total_allocated_instances", summary.getTotalAllocatedInstances());
		metadata.put("total_allocated_bytes", summary.getTotalAllocatedBytes());
		metadata.put("total_allocated_mb", round(summary.getTotalAllocatedBytes() / (1024.0 * 1024.0)));
		metadata.put("dump_time", summary.getTime());

		Properties sysProps = heap.getSystemProperties();
		if (sysProps != null) {
			metadata.put("java_version", sysProps.getProperty("java.version", "unknown"));
			metadata.put("java_vm_name", sysProps.getProperty("java.vm.name", "unknown"));
		}

		return metadata;
	}

	/**
	 * Rounds a double value to two decimal places.
	 *
	 * @param value the value to round
	 * @return the value rounded to two decimal places
	 */
	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	/**
	 * Prints a JSON error object containing {@code message} to standard output.
	 *
	 * @param message the error description to include in the JSON response
	 */
	private static void printError(String message) {
		Map<String, String> error = new LinkedHashMap<>();
		error.put("error", message);
		try {
			Console.root().println(MAPPER.writeValueAsString(error));
		} catch (IOException e) {
			Console.root().println("{\"error\": \"" + message + "\"}");
		}
	}

	/**
	 * Prints usage instructions as a JSON error and exits with status 1.
	 */
	private static void printUsageAndExit() {
		printError("Usage: java -jar ar-heap-analyzer.jar <histogram|dominators|summary> [--top N] <file.hprof>");
		System.exit(1);
	}

	/**
	 * Internal class for aggregating per-class statistics.
	 */
	private static class ClassStats {
		/** Fully qualified name of the Java class represented by these statistics. */
		private final String className;
		/** Number of live instances of this class observed in the heap snapshot. */
		private final int instanceCount;
		/** Total shallow (not retained) heap bytes occupied by all instances of this class. */
		private final long shallowSize;

		/**
		 * Creates a statistics record for a single class.
		 *
		 * @param className     the fully qualified class name
		 * @param instanceCount the number of instances found in the heap
		 * @param shallowSize   the total shallow size in bytes
		 */
		ClassStats(String className, int instanceCount, long shallowSize) {
			this.className = className;
			this.instanceCount = instanceCount;
			this.shallowSize = shallowSize;
		}

		String getClassName() { return className; }
		int getInstanceCount() { return instanceCount; }
		long getShallowSize() { return shallowSize; }
	}
}
