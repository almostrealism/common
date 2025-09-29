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

public class Console {
	public static Console root = new Console();
	public static boolean systemOutEnabled = true;

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

	protected Console() { this(null); }

	protected Console(Console parent) {
		this.parent = parent;
		this.format = DateTimeFormatter.ofPattern("HH:mm.ss");
		this.resetLastLine = true;
	}
	
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

	public Console addListener(Consumer<String> listener) {
		listeners.add(listener);
		return this;
	}

	public Console addFilter(UnaryOperator<String> filter) {
		filters.add(filter);
		return this;
	}

	public Console addAlertDeliveryProvider(AlertDeliveryProvider provider) {
		alertDeliveryProviders.add(provider);
		return this;
	}

	public <T> List<T> getSamples(String key) {
		return (List<T>) getSamples(key, true);
	}

	protected List<?> getSamples(String key, boolean create) {
		List<?> p = parent == null ? null : parent.getSamples(key, false);
		if (p != null) return p;

		return create ? samples.computeIfAbsent(key, k -> new ArrayList<>()) : samples.get(key);
	}

	public void warn(String message) { warn(message, null); }

	public void warn(String message, Throwable ex) {
		println("WARN: " + message);
		if (ex != null) ex.printStackTrace();
	}

	public DistributionMetric distribution(String name) {
		return distribution(name, 1.0);
	}

	public DistributionMetric distribution(String name, double scale) {
		if (metrics.containsKey(name)) {
			return (DistributionMetric) metrics.get(name);
		}

		DistributionMetric metric = new DistributionMetric(name, scale);
		metric.setConsole(this);
		metrics.put(name, metric);
		return metric;
	}

	public TimingMetric timing(String name) {
		if (metrics.containsKey(name)) {
			return (TimingMetric) metrics.get(name);
		}

		TimingMetric metric = new TimingMetric(name);
		metric.setConsole(this);
		metrics.put(name, metric);
		return metric;
	}

	public void alert(String message) {
		alert(Alert.Severity.INFO, message);
	}

	public void alert(String message, Throwable ex) {
		alert(Alert.forThrowable(message, ex));
	}

	public void alert(Alert.Severity severity, String message) {
		alert(new Alert(severity, message));
	}

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

	public void flag() { flag(""); }
	public void flag(String value) { this.flag = Optional.of(value); }

	public Optional<String> clearFlag() {
		try {
			return checkFlag();
		} finally {
			this.flag = null;
		}
	}

	public Optional<String> checkFlag() {
		return flag == null ? Optional.empty() : flag;
	}

	public Console child() {
		return new Console(this);
	}

	public <T> ConsoleFeatures features(T type) {
		return type instanceof Class ? features((Class) type) : features(type.getClass());
	}

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

	public static Console root() {
		return root;
	}
}
