/*
 * Copyright 2016 Michael Murray
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

/*
 * Copyright (C) 2005-06  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package org.almostrealism.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * ASCII chart visualization utility for displaying time-series data in text format.
 *
 * <p>This class maintains a rolling window of values and displays them as horizontal
 * bar charts with timestamps. It automatically tracks min/max values and marks local
 * extrema in the output.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Horizontal ASCII bar chart rendering</li>
 *   <li>Timestamp labels for each entry</li>
 *   <li>Automatic min/max range tracking</li>
 *   <li>Local minima/maxima annotation</li>
 *   <li>Configurable scale and divisions</li>
 *   <li>Rolling window (configurable maximum entries)</li>
 *   <li>Export values to file</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Chart chart = new Chart(50);  // Keep last 50 entries
 * chart.setScale(0.1);          // Each '#' represents 0.1 units
 * chart.setDivisions(60);       // 60 divisions wide
 *
 * // Add values over time
 * chart.addEntry(2.5);
 * chart.addEntry(3.1);
 * chart.addMessage("Processing started");
 * chart.addEntry(4.2);
 *
 * // Display chart
 * System.out.println(chart);
 *
 * // Save raw values
 * chart.storeValues(new File("output.txt"));
 * }</pre>
 *
 * <h2>Output Format</h2>
 * <pre>
 * Range: (2.500, 4.200)
 * [--------]:.*.*.*.*.*.*.*.*...
 *
 * [10:30 AM]:######################### 2.500
 * [10:31 AM]:############################### 3.100
 * [10:31 AM]: Processing started
 * [10:32 AM]:##########################################  4.200]
 * </pre>
 *
 * @author Mike Murray
 */
public class Chart extends ArrayList<String> {
	private static final String header = "[--------]:";
	private DateFormat format = new SimpleDateFormat("hh:mm a");
	private static NumberFormat dformat = new DecimalFormat("#.000");
	
	private int max = 100;
	private double scale = 0.05;
	private int div = 80;
	private int minMaxOffset = 1;
	private int sinceLastMin = 0, sinceLastMax = 0;
	private double minValue = Double.MAX_VALUE, maxValue;
	private double lastValue, currentValue;
	private List<Double> values;
	
	/**
	 * Creates a new chart with default maximum of 100 entries.
	 */
	public Chart() { this.values = new ArrayList<Double>(); }

	/**
	 * Creates a new chart with a specified maximum number of entries.
	 * Older entries are removed when the limit is exceeded.
	 *
	 * @param max the maximum number of entries to retain
	 */
	public Chart(int max) { this.values = new ArrayList<Double>(); this.max = max; }

	/**
	 * Adds a numeric value entry to the chart with a timestamp.
	 * The value is displayed as a horizontal bar of '#' characters.
	 * Local minima and maxima are automatically detected and annotated.
	 *
	 * @param a the value to add
	 */
	public void addEntry(double a) {
		this.values.add(new Double(a));
		
		Date now = new Date();
		
		StringBuffer b = new StringBuffer();
		b.append("[");
		b.append(format.format(now));
		b.append("]:");
		
		for (int i = 0; i < this.div; i++) if (a > i * this.scale) b.append("#");
		
		if (a < this.scale || a > this.scale * this.div) b.append(" " + dformat.format(a));
		
		if (super.size() > 2 && this.sinceLastMin > this.minMaxOffset &&
				lastValue <= currentValue && a < currentValue) {
			String s = (String) super.remove(super.size() - 1);
			super.add(s.concat("]                    " +  Chart.dformat.format(currentValue)));
			
			if (currentValue > this.maxValue) this.maxValue = currentValue;
			this.sinceLastMax = 0;
			this.sinceLastMin++;
		} else if (super.size() > 2 && this.sinceLastMax > this.minMaxOffset &&
				lastValue >= currentValue && a > currentValue) {
			String s = (String) super.remove(super.size() - 1);
			super.add(s.concat("[                    " + Chart.dformat.format(currentValue)));
			
			if (currentValue < this.minValue) this.minValue = currentValue;
			this.sinceLastMin = 0;
			this.sinceLastMax++;
		} else {
			this.sinceLastMin++;
			this.sinceLastMax++;
		}
		
		if (a != 0.0) {
			this.lastValue = this.currentValue;
			this.currentValue = a;
		}
		
		super.add(b.toString());
		if (super.size() > this.max) super.remove(0);
	}
	
	/**
	 * Adds a text message entry to the chart with a timestamp.
	 * Messages appear as labeled entries without bar visualization.
	 *
	 * @param msg the message to add
	 */
	public void addMessage(String msg) {
		Date now = new Date();
		
		StringBuffer b = new StringBuffer();
		b.append("[");
		b.append(format.format(now));
		b.append("]: ");
		b.append(msg);
		
		this.values.add(new Double(Float.MAX_VALUE));
		super.add(b.toString());
	}
	
	/**
	 * Sets the scale factor for bar rendering.
	 * Each '#' character represents this many units of value.
	 *
	 * @param d the scale factor (default is 0.05)
	 */
	public void setScale(double d) { this.scale = d; }

	/**
	 * Sets the maximum number of divisions (width) for bar rendering.
	 *
	 * @param i the number of divisions (default is 80)
	 */
	public void setDivisions(int i) { this.div = i; }

	/**
	 * Prints the chart to a StringBuffer, including header, range information,
	 * and all entries.
	 *
	 * @param buf the buffer to append the chart output to
	 */
	public void print(StringBuffer buf) {
		buf.append("Range: (");
		
		if (this.minValue < this.maxValue) {
			buf.append(dformat.format(this.minValue));
			buf.append(", ");
		}
		
		buf.append(dformat.format(this.maxValue));
		buf.append(")\n");
		buf.append(Chart.header);
		
		double s;
		for (int i = 1; i <= this.div; i++) {
			s = (i * this.scale) % 1;
			
			if (s < this.scale)
				buf.append("*");
			else
				buf.append(".");
		}
		
		buf.append("\n\n");
		
		Iterator<String> itr = super.iterator();
		while (itr.hasNext()) buf.append(itr.next() + "\n");
	}
	
	/**
	 * Returns the ASCII chart as a string.
	 *
	 * @return the complete chart output including header, range, and all entries
	 */
	public String toString() {
		StringBuffer b = new StringBuffer();
		this.print(b);
		return b.toString();
	}

	/**
	 * Writes all numeric values (one per line) to a file.
	 * Messages are represented as {@link Float#MAX_VALUE} in the output.
	 *
	 * @param f the file to write values to
	 * @throws IOException if the file cannot be written
	 */
	public void storeValues(File f) throws IOException {
		try (PrintStream p = new PrintStream(new FileOutputStream(f))) {
			Iterator<Double> itr = this.values.iterator();
			while(itr.hasNext()) p.println(itr.next());
			
			p.flush();
		}
	}
}
