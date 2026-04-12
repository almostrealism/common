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

package org.almostrealism.color;

import io.almostrealism.uml.Nameable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a piecewise-constant probability distribution over a continuous domain,
 * used to model spectral emission and wavelength sampling in physically-based rendering.
 *
 * <p>The distribution is stored as a binary search tree of non-overlapping intervals,
 * each with an associated probability density. Samples can be drawn using the
 * {@link #getSample(double)} method, and the distribution can be integrated to produce
 * an expected RGB color via {@link #getIntegrated()}.
 *
 * <p>Ranges are added with {@link #addRange(double, double, double)} and must not overlap.
 * A {@link Sampler} can be supplied to delegate sampling and probability queries.
 *
 * @author Mike Murray and Sam Tepper
 */
public class ProbabilityDistribution implements Nameable {
	/**
	 * Internal binary search tree node representing a probability interval.
	 *
	 * <p>Each node stores a range {@code [start, end]} and its associated
	 * probability density {@code p}. Child nodes to the left cover lower
	 * values and to the right cover higher values.
	 */
	private static class Node {
		/** The lower bound of the probability interval represented by this node. */
		double start;

		/** The upper bound of the probability interval represented by this node. */
		double end;

		/** The probability density associated with the interval {@code [start, end]}. */
		double p;

		/** The left child node covering intervals with smaller values. */
		private Node left;

		/** The right child node covering intervals with larger values. */
		private Node right;

		/**
		 * Constructs a node representing the interval {@code [start, end]} with density {@code p}.
		 *
		 * @param start the lower bound of the interval
		 * @param end   the upper bound of the interval
		 * @param p     the probability density over the interval
		 */
		public Node(double start, double end, double p) {
			this.start = start;
			this.end = end;
			this.p = p;
		}

		/**
		 * Inserts the given node into the subtree rooted at this node.
		 *
		 * @param n the node to insert (its interval must not overlap this node's interval)
		 */
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
		
		/**
		 * Returns the probability density at {@code x} by searching the subtree.
		 *
		 * @param x the domain value to look up
		 * @return the probability density at {@code x}, or {@code 0.0} if not covered
		 */
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
		
		/**
		 * Returns {@code true} if {@code x} is within the interval {@code [start, end]}.
		 *
		 * @param x the value to test
		 * @return {@code true} if this node's interval contains {@code x}
		 */
		public boolean contains(double x) {
			return (x >= this.start && x <= this.end);
		}

		/**
		 * Returns {@code true} if any node in this subtree overlaps the interval {@code [s, e]}.
		 *
		 * @param s the lower bound of the interval to test
		 * @param e the upper bound of the interval to test
		 * @return {@code true} if any node in the subtree contains part of the interval
		 */
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
		
		/**
		 * Returns the cumulative integral of the subtree up to {@code limit}.
		 *
		 * @param limit the upper bound of integration
		 * @return the total integrated probability density up to {@code limit}
		 */
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
		
		/**
		 * Performs an in-order traversal of the subtree, adding each node's range data
		 * to {@code l} as a {@code double[]{start, end, p}} entry.
		 *
		 * @param l the list to which range arrays are appended
		 * @return the total probability mass covered by this subtree
		 */
		public double getRanges(List l) {
			double tot = 0.0;
			if (this.left != null) tot += this.left.getRanges(l);
			l.add(new double[] {this.start, this.end, this.p});
			tot += (this.end - this.start) * this.p;
			if (this.right != null) tot += this.right.getRanges(l);
			return tot;
		}
		
		/**
		 * Returns the node at the given in-order index within this subtree.
		 *
		 * @param index the zero-based in-order position of the desired node
		 * @return the node at the specified in-order position, or {@code null} if out of range
		 */
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
		
		/**
		 * Returns the total number of nodes in this subtree, including this node.
		 *
		 * @return the node count for this subtree
		 */
		public int getNodeCount() {
			int c = 1;
			if (this.left != null) c = c + this.left.getNodeCount();
			if (this.right != null) c = c + this.right.getNodeCount();
			return c;
		}
	}
	
	/**
	 * Delegate interface for custom sampling and probability lookup strategies,
	 * allowing the probability distribution behavior to be overridden.
	 */
	public static interface Sampler {
		/**
		 * Returns a sample value drawn from the distribution using the random variate {@code r}.
		 *
		 * @param r a uniform random value in [0, 1]
		 * @return the sampled value from the distribution domain
		 */
		public double getSample(double r);

		/**
		 * Returns the probability density at the specified value.
		 *
		 * @param x the value at which to evaluate the probability density
		 * @return the probability density at {@code x}
		 */
		public double getProbability(double x);
	}
	
	/** The root node of the binary search tree, or {@code null} if no ranges have been added. */
	private Node root;

	/** A cache of all ranges in sorted order, rebuilt when the tree changes. */
	private double ranges[][];

	/** The cached integrated RGB color, or {@code null} if it needs to be recomputed. */
	private RGB integrated;

	/** The total probability mass (sum of {@code (end - start) * p} across all nodes). */
	private double tot;

	/** An optional delegate used for sampling and probability lookup. */
	private Sampler sampler;

	/** An optional name for this distribution. */
	private String name;

	/**
	 * Constructs an empty {@link ProbabilityDistribution} with no ranges.
	 */
	public ProbabilityDistribution() { }

	/**
	 * Constructs a {@link ProbabilityDistribution} that delegates sampling and
	 * probability queries to the given {@link Sampler}.
	 *
	 * @param s the sampler to use for {@link #getSample(double)} and {@link #getProbability(double)}
	 */
	public ProbabilityDistribution(Sampler s) { this.sampler = s; }

	/**
	 * Adds a probability range {@code [start, end]} with the given density {@code p}.
	 *
	 * <p>The probability is clamped to [0, 1]. Ranges must not overlap any existing range.</p>
	 *
	 * @param start the lower bound of the range
	 * @param end   the upper bound of the range
	 * @param p     the probability density (clamped to [0, 1])
	 * @throws IllegalArgumentException if the range overlaps an existing range
	 */
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
	
	/**
	 * Rebuilds the sorted ranges cache from the binary search tree.
	 *
	 * <p>This method performs an in-order traversal and updates {@link #tot}.</p>
	 *
	 * @return a 2D array of {@code [start, end, probability]} triples in sorted order
	 */
	protected double[][] getRanges() {
		if (this.root == null) return new double[0][3];

		List l = new ArrayList();
		this.tot = this.root.getRanges(l);

		return (double[][]) l.toArray(new double[0][3]);
	}

	/**
	 * Returns the probability density of the range at the given in-order index.
	 *
	 * @param index the zero-based in-order index of the range
	 * @return the probability density of the specified range
	 */
	public double getRangeProbability(int index) {
		return this.root.getNode(index).p;
	}

	/**
	 * Sets the probability density of the range at the given in-order index and
	 * rebuilds the ranges cache.
	 *
	 * @param index the zero-based in-order index of the range to modify
	 * @param p     the new probability density
	 */
	public void setRangeProbability(int index, double p) {
		this.root.getNode(index).p = p;
		this.ranges = this.getRanges();
		this.integrated = null;
	}

	/**
	 * Returns a domain value sampled according to the distribution.
	 *
	 * <p>If a {@link Sampler} is set, it is used instead. Otherwise, the sample is
	 * found by walking the sorted ranges cache until the cumulative probability
	 * reaches {@code r}.</p>
	 *
	 * @param r a uniform random variate in [0, 1]
	 * @return the sampled domain value
	 */
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
	
	/**
	 * Returns the cumulative integral of the distribution from the minimum range value up to {@code limit}.
	 *
	 * @param limit the upper bound of integration
	 * @return the integrated probability density up to {@code limit}, or {@code 0.0} if empty
	 */
	public double integrate(double limit) {
		if (this.root == null)
			return 0.0;
		else
			return this.root.integrate(limit);
	}

	/**
	 * Returns the expected RGB color of this distribution by integrating over 1000 wavelength steps.
	 *
	 * <p>The result is cached after the first call and invalidated when ranges change.</p>
	 *
	 * @return the integrated {@link RGB} color
	 */
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
	
	/**
	 * Returns the probability density at {@code x}.
	 *
	 * <p>Delegates to the {@link Sampler} if one is set; otherwise searches the tree.</p>
	 *
	 * @param x the domain value at which to evaluate the probability density
	 * @return the probability density at {@code x}, or {@code 0.0} if not covered
	 */
	public double getProbability(double x) {
		if (this.sampler != null)
			return this.sampler.getProbability(x);
		else
			return this.root.get(x);
	}
	
	/**
	 * Returns the total number of range nodes in the binary search tree.
	 *
	 * @return the node count
	 */
	public int getNodeCount() { return this.root.getNodeCount(); }

	/**
	 * Returns {@code true} if any existing range overlaps {@code [start, end]}.
	 *
	 * @param start the lower bound of the range to test
	 * @param end   the upper bound of the range to test
	 * @return {@code true} if the range overlaps an existing range
	 */
	public boolean contains(double start, double end) {
		if (this.root == null)
			return false;
		else
			return this.root.contains(start, end);
	}
	
	/**
	 * Loads probability ranges from a delimited file.
	 *
	 * <p>Each line must contain three columns: {@code start}, {@code end}, and {@code p}.</p>
	 *
	 * @param file the path to the input file
	 * @param div  the column delimiter
	 * @throws IOException if the file cannot be read
	 */
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
	
	/**
	 * Sets the name of this distribution.
	 *
	 * @param s the new name
	 */
	@Override
	public void setName(String s) { this.name = s; }

	/** {@inheritDoc} */
	@Override
	public String getName() { return this.name; }
}
