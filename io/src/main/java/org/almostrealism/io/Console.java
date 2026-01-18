/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.io;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A hierarchical, extensible logging and monitoring system for the Almost Realism framework.
 *
 * <p>{@code Console} provides a sophisticated logging infrastructure with the following features:</p>
 * <ul>
 *   <li><b>Hierarchical Structure:</b> Child consoles inherit from parent consoles, allowing scoped logging</li>
 *   <li><b>Timestamped Output:</b> All messages are automatically timestamped</li>
 *   <li><b>Extensible Listeners:</b> Attach custom listeners to capture output (e.g., file writers)</li>
 *   <li><b>Output Filtering:</b> Apply filters to modify or suppress output</li>
 *   <li><b>Performance Metrics:</b> Built-in timing and distribution metrics</li>
 *   <li><b>Alert System:</b> Deliver alerts through pluggable providers</li>
 *   <li><b>Sample Collection:</b> Collect and retrieve typed samples for analysis</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * Console console = Console.root();
 * console.println("Hello, world!");
 * console.warn("Something might be wrong");
 * }</pre>
 *
 * <h2>File Output</h2>
 * <pre>{@code
 * Console.root().addListener(OutputFeatures.fileOutput("/path/to/log.txt"));
 * Console.root().println("Logged to both console and file");
 * }</pre>
 *
 * <h2>Child Consoles</h2>
 * <pre>{@code
 * Console parent = Console.root();
 * Console child = parent.child();
 * child.println("This propagates to parent");
 * }</pre>
 *
 * <h2>Performance Metrics</h2>
 * <pre>{@code
 * TimingMetric timing = Console.root().timing("operationName");
 * timing.measure("step1", () -> doStep1());
 * Console.root().println(timing.summary());
 * }</pre>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AR_IO_SYSOUT=false} - Disable System.out output (only listeners receive output)</li>
 * </ul>
 *
 * @see ConsoleFeatures
 * @see OutputFeatures
 * @see TimingMetric
 * @see DistributionMetric
 */
public class Console {
	public static Console root = new Console();
	public static boolean systemOutEnabled = SystemUtils.isEnabled("AR_IO_SYSOUT").orElse(true);

	private Console parent;
	private List<Consumer<String>> listeners = new ArrayList<>();
	private List<UnaryOperator<String>> filters = new ArrayList<>();
	private List<AlertDeliveryProvider> alertDeliveryProviders = new ArrayList<>();
	private Optional<String> flag;

	private DateTimeFormatter format;
	private StringBuffer data = new StringBuffer();
	private StringBuffer lastLine;
	private boolean resetLastLine;

	private Map<String, MetricBase> metrics = Collections.synchronizedMap(new HashMap<>());
	private Map<String, List<?>> samples = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Creates the root console instance.
	 */
	protected Console() { this(null); }

	/**
	 * Creates a child console with the specified parent.
	 *
	 * @param parent the parent console, or null for root console
	 */
	protected Console(Console parent) {
		this.parent = parent;
		this.format = DateTimeFormatter.ofPattern("HH:mm.ss");
		this.resetLastLine = true;
	}

	/**
	 * Prints a string to the console without adding a newline.
	 * The string is timestamped, filtered, and sent to all listeners.
	 *
	 * @param s the string to print
	 */
	public void print(String s) {
		String orig = s;
		s = prep(s);
		if (s == null) return;
		
		append(s);
		lastLine.append(s);
		
		if (parent == null) {
			if (systemOutEnabled) System.out.print(s);
		} else {
			parent.print(orig);
		}
	}
	
	/**
	 * Prints a string followed by a newline to the console.
	 * The string is timestamped, filtered, and sent to all listeners.
	 *
	 * @param s the string to print
	 */
	public void println(String s) {
		String orig = s;
		s = prep(s);
		if (s == null) return;

		append(s);
		append("\n");
		
		lastLine.append(s);
		resetLastLine = true;
		
		if (parent == null) {
			if (systemOutEnabled) System.out.println(s);
		} else {
			parent.println(orig);
		}
	}
	
	/**
	 * Prints a newline to the console.
	 */
	public void println() {
		if (resetLastLine) {
			lastLine = new StringBuffer();
		}
		
		append("\n");
		resetLastLine = true;
		
		if (parent == null) {
			if (systemOutEnabled) System.out.println();
		} else {
			parent.println();
		}
	}
	
	/**
	 * Returns the last line that was printed to the console.
	 *
	 * @return the last line, without timestamp prefix
	 */
	public String lastLine() { return lastLine.toString(); }

	protected void append(String s) {
		data.append(s);

		for (Consumer<String> listener : listeners) {
			try {
				listener.accept(s);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	protected String pre() {
		return "[" + format.format(java.time.LocalTime.now()) + "] ";
	}

	protected String prep(String s) {
		for (UnaryOperator<String> filter : filters) {
			s = filter.apply(s);
		}

		if (s == null) return s;

		if (resetLastLine) {
			lastLine = new StringBuffer();
			s = pre() + s;
			resetLastLine = false;
		}

		return s;
	}

	/**
	 * Adds a listener that will be notified of all output.
	 * Listeners receive raw output including timestamps and newlines.
	 * Commonly used for file output via {@link OutputFeatures#fileOutput(String)}.
	 *
	 * @param listener the listener to add
	 * @return this console for method chaining
	 */
	public Console addListener(Consumer<String> listener) {
		listeners.add(listener);
		return this;
	}

	/**
	 * Adds a filter that can modify or suppress output before it is displayed.
	 * Filters returning null will suppress the output entirely.
	 *
	 * @param filter the filter to add
	 * @return this console for method chaining
	 * @see ConsoleFeatures#duplicateFilter(long)
	 */
	public Console addFilter(UnaryOperator<String> filter) {
		filters.add(filter);
		return this;
	}

	/**
	 * Adds an alert delivery provider for custom alert handling.
	 * Providers are notified when {@link #alert(Alert)} is called.
	 *
	 * @param provider the alert delivery provider to add
	 * @return this console for method chaining
	 */
	public Console addAlertDeliveryProvider(AlertDeliveryProvider provider) {
		alertDeliveryProviders.add(provider);
		return this;
	}

	/**
	 * Gets or creates a typed list of samples associated with the given key.
	 * Samples can be used to collect data for later analysis or reporting.
	 *
	 * @param <T> the type of samples
	 * @param key the key identifying the sample collection
	 * @return a mutable list of samples
	 */
	public <T> List<T> getSamples(String key) {
		return (List<T>) getSamples(key, true);
	}

	/**
	 * Gets samples from this console or its parent.
	 *
	 * @param key the key identifying the sample collection
	 * @param create whether to create the list if it doesn't exist
	 * @return the sample list, or null if not found and create is false
	 */
	protected List<?> getSamples(String key, boolean create) {
		List<?> p = parent == null ? null : parent.getSamples(key, false);
		if (p != null) return p;

		return create ? samples.computeIfAbsent(key, k -> new ArrayList<>()) : samples.get(key);
	}

	/**
	 * Prints a warning message to the console.
	 *
	 * @param message the warning message
	 */
	public void warn(String message) { warn(message, null); }

	/**
	 * Prints a warning message with an optional exception to the console.
	 * If an exception is provided, its stack trace is printed.
	 *
	 * @param message the warning message
	 * @param ex the exception, or null
	 */
	public void warn(String message, Throwable ex) {
		println("WARN: " + message);
		if (ex != null) ex.printStackTrace();
	}

	/**
	 * Gets or creates a distribution metric with the given name.
	 * Distribution metrics track numeric values and compute statistics.
	 *
	 * @param name the metric name
	 * @return a distribution metric instance
	 */
	public DistributionMetric distribution(String name) {
		return distribution(name, 1.0);
	}

	/**
	 * Gets or creates a distribution metric with the given name and scale.
	 * The scale is used to convert raw values to display units.
	 *
	 * @param name the metric name
	 * @param scale the scale factor for display (e.g., 1e9 for nanoseconds to seconds)
	 * @return a distribution metric instance
	 */
	public DistributionMetric distribution(String name, double scale) {
		if (metrics.containsKey(name)) {
			return (DistributionMetric) metrics.get(name);
		}

		DistributionMetric metric = new DistributionMetric(name, scale);
		metric.setConsole(this);
		metrics.put(name, metric);
		return metric;
	}

	/**
	 * Gets or creates a timing metric with the given name.
	 * Timing metrics measure execution times in nanoseconds and report in seconds.
	 *
	 * @param name the metric name
	 * @return a timing metric instance
	 * @see TimingMetric#measure(String, Runnable)
	 */
	public TimingMetric timing(String name) {
		if (metrics.containsKey(name)) {
			return (TimingMetric) metrics.get(name);
		}

		TimingMetric metric = new TimingMetric(name);
		metric.setConsole(this);
		metrics.put(name, metric);
		return metric;
	}

	/**
	 * Sends an informational alert.
	 *
	 * @param message the alert message
	 */
	public void alert(String message) {
		alert(Alert.Severity.INFO, message);
	}

	/**
	 * Sends an error alert for an exception.
	 *
	 * @param message the alert message
	 * @param ex the exception
	 */
	public void alert(String message, Throwable ex) {
		alert(Alert.forThrowable(message, ex));
	}

	/**
	 * Sends an alert with the specified severity.
	 *
	 * @param severity the alert severity
	 * @param message the alert message
	 */
	public void alert(Alert.Severity severity, String message) {
		alert(new Alert(severity, message));
	}

	/**
	 * Sends an alert through all registered alert delivery providers.
	 * If no providers are registered and this is not a child console,
	 * the alert is printed to the console.
	 *
	 * @param message the alert
	 */
	public void alert(Alert message) {
		alertDeliveryProviders.forEach(provider -> provider.sendAlert(message));

		boolean delivered = !alertDeliveryProviders.isEmpty();

		if (parent != null) {
			parent.alert(message);
			delivered = true;
		}

		if (!delivered) {
			println("[ALERT]" + message);
		}
	}

	/**
	 * Sets a flag with an empty value. Flags can be used for simple signaling.
	 */
	public void flag() { flag(""); }

	/**
	 * Sets a flag with the specified value.
	 *
	 * @param value the flag value
	 */
	public void flag(String value) { this.flag = Optional.of(value); }

	/**
	 * Clears and returns the current flag value.
	 *
	 * @return the flag value before clearing, or empty if no flag was set
	 */
	public Optional<String> clearFlag() {
		try {
			return checkFlag();
		} finally {
			this.flag = null;
		}
	}

	/**
	 * Checks the current flag value without clearing it.
	 *
	 * @return the current flag value, or empty if no flag is set
	 */
	public Optional<String> checkFlag() {
		return flag == null ? Optional.empty() : flag;
	}

	/**
	 * Creates a child console that inherits from this console.
	 * Output to the child is propagated to this parent.
	 *
	 * @return a new child console
	 */
	public Console child() {
		return new Console(this);
	}

	/**
	 * Creates a {@link ConsoleFeatures} instance for the given object's class.
	 *
	 * @param <T> the object type
	 * @param type the object to create features for
	 * @return console features for the object's class
	 */
	public <T> ConsoleFeatures features(T type) {
		return type instanceof Class ? features((Class) type) : features(type.getClass());
	}

	/**
	 * Creates a {@link ConsoleFeatures} instance for the given class.
	 * The class name will be used as a prefix for all log messages.
	 *
	 * @param cls the class to create features for
	 * @return console features for the class
	 */
	public ConsoleFeatures features(Class cls) {
		return new ConsoleFeatures() {
			@Override
			public Class getLogClass() {
				return cls;
			}

			@Override
			public Console console() {
				return Console.this;
			}
		};
	}

	/**
	 * Returns the root console instance.
	 * This is the primary entry point for logging in the framework.
	 *
	 * @return the root console
	 */
	public static Console root() {
		return root;
	}
}
