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
	private StringBuffer data = new StringBuffer();
	private StringBuffer lastLine = new StringBuffer();
	private boolean resetLastLine = false;

	private Map<String, TimingMetric> metrics = Collections.synchronizedMap(new HashMap<>());

	protected Console() { this(null); }

	protected Console(Console parent) {
		this.parent = parent;
	}
	
	public void print(String s) {
		if (resetLastLine) lastLine = new StringBuffer();
		
		append(s);
		lastLine.append(s);
		
		if (parent == null) {
			if (systemOutEnabled) System.out.print(s);
		} else {
			parent.print(s);
		}
	}
	
	public void println(String s) {
		if (resetLastLine) lastLine = new StringBuffer();
		
		append(s);
		append("\n");
		
		lastLine.append(s);
		resetLastLine = true;
		
		if (parent == null) {
			if (systemOutEnabled) System.out.println(s);
		} else {
			parent.println(s);
		}
	}
	
	public void println() {
		if (resetLastLine) lastLine = new StringBuffer();
		
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

	public void addListener(Consumer<String> listener) {
		listeners.add(listener);
	}

	public void warn(String message) { warn(message, null); }

	public void warn(String message, Throwable ex) {
		println("WARN: " + message);
		if (ex != null) ex.printStackTrace();
	}

	public TimingMetric metric(String name) {
		if (metrics.containsKey(name)) {
			return metrics.get(name);
		}

		TimingMetric metric = new TimingMetric(name);
		metrics.put(name, metric);
		return metric;
	}

	public Console child() {
		return new Console(this);
	}

	public static Console root() {
		return root;
	}
}
