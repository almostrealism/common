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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.almostrealism.color.RGB;

/**
 * @author  Mike Murray
 */
public class RangeSumDistribution extends ProbabilityDistribution {
	private ProbabilityDistribution children[];
	private double e = Math.pow(10.0, -8.0);
	
	public RangeSumDistribution() {
		this(new ProbabilityDistribution[0]);
	}
	
	public RangeSumDistribution(ProbabilityDistribution children[]) {
		this.children = children;
	}
	
	public void addRange(double start, double end, double p) {
		if (this.children.length <= 0) return;
		this.children[0].addRange(start, end, p);
	}
	
	protected double[][] getRanges() {
		if (this.children.length <= 0) return new double[0][0];
		if (this.children.length == 1) return this.children[0].getRanges();
		
		List l = new ArrayList();
		
		double ranges[][] = this.children[0].getRanges();
		for (int i = 0; i < ranges.length; i++) l.add(ranges[i]);
		
		for (int i = 1; i < this.children.length; i++) {
			ranges = this.children[i].getRanges();
			
			for (int j = 0; j < ranges.length; j++) {
				Iterator itr = l.iterator();
				
				boolean in = false;
				double sp, sq, fp, fq;
				double s0, s1, s2;
				double f0, f1, f2;
				
				w: while (itr.hasNext()) {
					double r[] = (double[]) itr.next();
					
					if (ranges[j][0] < r[0] && !in) {
						s0 = ranges[j][0];
						s1 = r[0] - e;
						s2 = Double.NaN;
						
						sp = ranges[j][2];
						
						in = true;
						continue w;
					} else if (ranges[j][0] <= r[1] && !in) {
						s0 = r[0];
						s1 = ranges[j][0];
						s2 = r[1];
						
						sp = r[2];
						sq = ranges[j][2] + r[2];
						
						in = true;
						itr.remove();
						continue w;
					}
					
					if (ranges[j][1] >= r[1] && in) {
						r[2] += ranges[j][2];
						if (ranges[j][1] == r[1]) break w;
					} else if (ranges[j][1] > r[0] && in) {
						f0 = r[0];
						f1 = ranges[j][1];
						f2 = r[1];
						
						fp = ranges[j][2] + r[2];
						fq = r[2];
						
						itr.remove();
						break w;
					}
				}
				
				// TODO
			}
		}
		
		return (double[][]) l.toArray(new double[0][3]);
	}
	
	public double getSample(double r) {
		// TODO
		return 0.0;
	}
	
	public RGB getIntegrated() {
		// TODO
		return null;
	}
	
	public double getProbability(double x) {
		double p = 0.0;
		
		for (int i = 0; i < this.children.length; i++)
			p += this.children[i].getProbability(x);
		
		return p;
	}
	
	public boolean contains(double start, double end) {
		for (int i = 0; i < this.children.length; i++)
			if (this.children[i].contains(start, end)) return true;
		return false;
	}
	
	public void loadFromFile(String file, String div) throws IOException {
		this.children = new ProbabilityDistribution[1];
		this.children[0] = new ProbabilityDistribution();
		this.children[0].loadFromFile(file, div);
	}
	
	public static Method getOverlayMethod() {
		try {
			return RangeSumDistribution.class.getMethod("createRangeSumDistribution",
								new Class[] {ProbabilityDistribution[].class});
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static RangeSumDistribution
				createRangeSumDistribution(ProbabilityDistribution children[]) {
		return new RangeSumDistribution(children);
	}
}
