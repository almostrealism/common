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
 * @author Mike Murray
 */
public class Graph extends ArrayList {
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
	private List values;
	
	public Graph() { this.values = new ArrayList(); }
	
	public Graph(int max) { this.values = new ArrayList(); this.max = max; }
	
	public void addEntry(double a) {
		this.values.add(new Double(a));
		
		Date now = new Date();
		
		StringBuffer b = new StringBuffer();
		b.append("[");
		b.append(format.format(now));
		b.append("]:");
		
		for (int i = 0; i < this.div; i++) if (a > i * this.scale) b.append("#");
		
		if (a < this.scale || a > this.scale * this.div) b.append(" " + this.dformat.format(a));
		
		if (super.size() > 2 && this.sinceLastMin > this.minMaxOffset &&
				lastValue <= currentValue && a < currentValue) {
			String s = (String) super.remove(super.size() - 1);
			super.add(s.concat("]                    " +  Graph.dformat.format(currentValue)));
			
			if (currentValue > this.maxValue) this.maxValue = currentValue;
			this.sinceLastMax = 0;
			this.sinceLastMin++;
		} else if (super.size() > 2 && this.sinceLastMax > this.minMaxOffset &&
				lastValue >= currentValue && a > currentValue) {
			String s = (String) super.remove(super.size() - 1);
			super.add(s.concat("[                    " + Graph.dformat.format(currentValue)));
			
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
	
	public void setScale(double d) { this.scale = d; }
	
	public void setDivisions(int i) { this.div = i; }
	
	public void print(StringBuffer buf) {
		buf.append("Range: (");
		
		if (this.minValue < this.maxValue) {
			buf.append(this.dformat.format(this.minValue));
			buf.append(", ");
		}
		
		buf.append(this.dformat.format(this.maxValue));
		buf.append(")\n");
		buf.append(Graph.header);
		
		double s;
		for (int i = 1; i <= this.div; i++) {
			s = (i * this.scale) % 1;
			
			if (s < this.scale)
				buf.append("*");
			else
				buf.append(".");
		}
		
		buf.append("\n\n");
		
		Iterator itr = super.iterator();
		while (itr.hasNext()) buf.append(itr.next() + "\n");
	}
	
	public String toString() {
		StringBuffer b = new StringBuffer();
		this.print(b);
		return b.toString();
	}
	
	public void storeValues(File f) throws IOException {
		try (PrintStream p = new PrintStream(new FileOutputStream(f))) {
			Iterator itr = this.values.iterator();
			while(itr.hasNext()) p.println(itr.next());
			
			p.flush();
		}
	}
}
