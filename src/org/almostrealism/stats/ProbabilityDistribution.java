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

package org.almostrealism.stats;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.almostrealism.color.RGB;
import org.almostrealism.util.*;

/**
 * @author Mike Murray and Sam Tepper
 */
public class ProbabilityDistribution implements Nameable {
	private class Node {
		double start, end, p;
		private Node left, right;
		
		public Node(double start, double end, double p) {
			this.start = start;
			this.end = end;
			this.p = p;
		}
		
		public void add(Node n) {
			if (n.start > this.end) {
				if (this.right == null)
					this.right = n;
				else
					this.right.add(n);
			} else if (n.end < this.start) {
				if (this.left == null)
					this.left = n;
				else
					this.left.add(n);
			}
		}
		
		public double get(double x) {
			if (this.contains(x)) {
				return this.p;
			} else if (x > this.end && this.right != null) {
				return this.right.get(x);
			} else if (x < this.start && this.left != null) {
				return this.left.get(x);
			} else {
				return 0.0;
			}
		}
		
		public boolean contains(double x) {
			return (x >= this.start && x <= this.end);
		}
		
		public boolean contains(double s, double e) {
			if (s >= this.start && s <= this.end)
				return true;
			else if (e >= this.start && e <= this.end)
				return true;
			else if (this.left != null && this.left.contains(s, e))
				return true;
			else if (this.right != null && this.right.contains(s, e))
				return true;
			else
				return false;
		}
		
		public double integrate(double limit) {
			if (limit < this.start) {
				if (this.left != null) return this.left.integrate(limit);
			} else if (limit > this.end) {
				double c = (this.end - this.start) * this.p;
				if (this.left != null) c = c + this.left.integrate(limit);
				if (this.right != null) c = c + this.right.integrate(limit);
				return c;
			} else {
				double c = (limit - this.start) * this.p;
				if (this.left != null) c = c + this.left.integrate(limit);
				return c;
			}
			
			return 0.0;
		}
		
		public double getRanges(List l) {
			double tot = 0.0;
			if (this.left != null) tot += this.left.getRanges(l);
			l.add(new double[] {this.start, this.end, this.p});
			tot += (this.end - this.start) * this.p;
			if (this.right != null) tot += this.right.getRanges(l);
			return tot;
		}
		
		public Node getNode(int index) {
			int l = 0;
			if (this.left != null) l = this.left.getNodeCount();
			
			if (index < l)
				return this.left.getNode(index);
			else if (index == l)
				return this;
			else if (this.right != null)
				return this.right.getNode(index - l - 1);
			else
				return null;
		}
		
		public int getNodeCount() {
			int c = 1;
			if (this.left != null) c = c + this.left.getNodeCount();
			if (this.right != null) c = c + this.right.getNodeCount();
			return c;
		}
	}
	
	public static interface Sampler {
		public double getSample(double r);
		public double getProbability(double x);
	}
	
	private Node root;
	private double ranges[][];
	private RGB integrated;
	private double tot;
	private Sampler sampler;
	private String name;
	
	public ProbabilityDistribution() { }
	public ProbabilityDistribution(Sampler s) { this.sampler = s; }
	
	public void addRange(double start, double end, double p) {
		if (p > 1.0) p = 1.0;
		if (p < 0.0) p = 0.0;
		
		if (this.contains(start, end))
			throw new IllegalArgumentException("Overlapping ranges for distribution.");
		
		if (this.root == null)
			this.root = new Node(start, end, p);
		else
			this.root.add(new Node(start, end, p));
		
		this.ranges = this.getRanges();
		this.integrated = null;
	}
	
	protected double[][] getRanges() {
		if (this.root == null) return new double[0][3];
		
		List l = new ArrayList();
		this.tot = this.root.getRanges(l);
		
		return (double[][]) l.toArray(new double[0][3]);
	}
	
	public double getRangeProbability(int index) {
		return this.root.getNode(index).p;
	}
	
	public void setRangeProbability(int index, double p) {
		this.root.getNode(index).p = p;
		this.ranges = this.getRanges();
		this.integrated = null;
	}
	
	public double getSample(double r) {
		if (this.sampler != null) return this.sampler.getSample(r);
		if (this.root == null) return 0.0;
		
		double s = 0.0;
		int i = 0;
		double d = this.ranges[i][2] * (this.ranges[i][1] - this.ranges[i][0]) / this.tot;
		
		while (s + d < r) {
			s = s + d;
			i++;
			d = this.ranges[i][2] * (this.ranges[i][1] - this.ranges[i][0]) / this.tot;
		}
		
		return this.ranges[i][0] + (r - s) * this.tot / this.ranges[i][2];
	}
	
	public double integrate(double limit) {
		if (this.root == null)
			return 0.0;
		else
			return this.root.integrate(limit);
	}
	
	public RGB getIntegrated() {
		if (this.integrated != null) return this.integrated;
		
		double left = this.ranges[0][0];
		double right = this.ranges[this.ranges.length - 1][1];
		double delta = (right - left) / 1000.0;
		
		this.integrated = new RGB(0.0, 0.0, 0.0);
		
		for (int i = 0; i < 1000; i++) {
			double d = left + delta * i;
			RGB c = new RGB(1000.0 * d);
			c.multiplyBy(delta * this.getProbability(left + delta * i) / this.tot);
			this.integrated.addTo(c);
		}
		
		return integrated;
	}
	
	public double getProbability(double x) {
		if (this.sampler != null)
			return this.sampler.getProbability(x);
		else
			return this.root.get(x);
	}
	
	public int getNodeCount() { return this.root.getNodeCount(); }
	
	public boolean contains(double start, double end) {
		if (this.root == null)
			return false;
		else
			return this.root.contains(start, end);
	}
	
	public void loadFromFile(String file, String div) throws IOException {
		BufferedReader in = new BufferedReader(new FileReader(file));
		String line = null;
		
		while ((line = in.readLine()) != null) {
			String s[] = line.split(div);
			double start = Double.parseDouble(s[0]);
			double end = Double.parseDouble(s[1]);
			double p = Double.parseDouble(s[2]);
			this.addRange(start, end, p);
		}
	}
	
	public void setName(String s) { this.name = s; }
	public String getName() { return this.name; }
}
