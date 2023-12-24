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
import java.util.function.Consumer;

public class Console {
	public static Console root = new Console();
	public static boolean systemOutEnabled = true;

	private Console parent;
	private List<Consumer<String>> listeners = new ArrayList<>();
	private boolean flag;

	private DateTimeFormatter format;
	private StringBuffer data = new StringBuffer();
	private StringBuffer lastLine;
	private boolean resetLastLine;

	private Map<String, MetricBase> metrics = Collections.synchronizedMap(new HashMap<>());

	protected Console() { this(null); }

	protected Console(Console parent) {
		this.parent = parent;
		this.format = DateTimeFormatter.ofPattern("HH:mm.ss");
		this.resetLastLine = true;
	}
	
	public void print(String s) {
		String orig = s;
		s = prep(s);
		
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
		if (resetLastLine) {
			lastLine = new StringBuffer();
			s = pre() + s;
			resetLastLine = false;
		}

		return s;
	}

	public void addListener(Consumer<String> listener) {
		listeners.add(listener);
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

	public void flag() { this.flag = true; }
	public boolean clearFlag() { boolean f = this.flag; this.flag = false; return f; }
	public boolean checkFlag() { return this.flag; }

	public Console child() {
		return new Console(this);
	}

	public static Console root() {
		return root;
	}
}
