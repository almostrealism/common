/*
 * Copyright 2020 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.space;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.ArrayDeque;

/**
 * An L-System (Lindenmayer System) that generates fractal 3D surface arrangements
 * by iteratively applying rewriting rules to a symbol sequence.
 *
 * <p>Rules map symbol objects to arrays (or {@link Statement} lambdas) that replace
 * each symbol during each generation step.  Once the desired number of generations
 * is reached, the symbol sequence can be interpreted as a series of movement and
 * rotation commands that build a list of {@link AbstractSurface} objects placed
 * in 3D space.</p>
 *
 * @author Michael Murray
 */
public class LSystem implements CodeFeatures, ConsoleFeatures {
	/** Symbol that advances the cursor one step in the current direction and places a surface. */
	public static final String STEP = "step";

	/** Symbol that rotates the cursor forward (positive X rotation). */
	public static final String FORWARD = "forward";

	/** Symbol that rotates the cursor backward (negative X rotation). */
	public static final String BACKWARD = "backward";

	/** Symbol that rotates the cursor to the left (negative Z rotation). */
	public static final String LEFT = "left";

	/** Symbol that rotates the cursor to the right (positive Z rotation). */
	public static final String RIGHT = "right";

	/** Symbol that saves the current cursor position and direction onto a stack. */
	public static final String PUSH = "push";

	/** Symbol that restores the cursor position and direction from the stack. */
	public static final String POP = "pop";

	/** Factory interface for producing the next {@link AbstractSurface} in the L-System sequence. */
	public interface SurfaceFactory { AbstractSurface next(AbstractSurface current); }

	/** Interface for dynamic rule evaluation: maps an input object to a replacement symbol array. */
	public interface Statement { String[] evaluate(Object o); }

	/** The rewriting rules that map symbols to replacement sequences. */
	private final Hashtable rules;

	/** Factory that creates the {@link AbstractSurface} objects placed at each STEP. */
	private SurfaceFactory factory;

	/** Rotation applied when the LEFT symbol is encountered. */
	private Vector left;

	/** Rotation applied when the RIGHT symbol is encountered. */
	private Vector right;

	/** Rotation applied when the FORWARD symbol is encountered. */
	private Vector forward;

	/** Rotation applied when the BACKWARD symbol is encountered. */
	private Vector backward;
	
	/**
	 * Constructs an {@link LSystem} with the given rewriting rules and a default rotation angle of 30 degrees.
	 *
	 * @param rules the rewriting rules mapping symbols to replacement sequences
	 */
	public LSystem(Hashtable rules) {
		this.rules = rules;
		this.setAngle(Math.toRadians(30));
	}
	
	/**
	 * Sets the rotation angle used for the LEFT, RIGHT, FORWARD, and BACKWARD directions.
	 *
	 * @param angle the rotation angle in radians
	 */
	public void setAngle(double angle) {
		this.left = new Vector(0.0, 0.0, -angle);
		this.right = new Vector(0.0, 0.0, angle);
		this.forward = new Vector(angle, 0.0, 0.0);
		this.backward = new Vector(-angle, 0.0, 0.0);
	}
	
	/** Sets the rotation vector applied for the LEFT symbol. */
	public void setLeft(Vector left) { this.left = left; }

	/** Sets the rotation vector applied for the RIGHT symbol. */
	public void setRight(Vector right) { this.right = right; }

	/** Sets the rotation vector applied for the FORWARD symbol. */
	public void setForward(Vector forward) { this.forward = forward; }

	/** Sets the rotation vector applied for the BACKWARD symbol. */
	public void setBackward(Vector backward) { this.backward = backward; }

	/** Returns the rotation vector applied for the LEFT symbol. */
	public Vector getLeft() { return this.left; }

	/** Returns the rotation vector applied for the RIGHT symbol. */
	public Vector getRight() { return this.right; }

	/** Returns the rotation vector applied for the FORWARD symbol. */
	public Vector getForward() { return this.forward; }

	/** Returns the rotation vector applied for the BACKWARD symbol. */
	public Vector getBackward() { return this.backward; }

	/** Sets the factory used to create {@link AbstractSurface} objects at each STEP. */
	public void setSurfaceFactory(SurfaceFactory f) { this.factory = f; }

	/** Returns the factory used to create {@link AbstractSurface} objects at each STEP. */
	public SurfaceFactory getSurfaceFactory() { return this.factory; }

	/**
	 * Generates the symbol sequence after the given number of rewriting iterations.
	 *
	 * @param init the initial symbol list
	 * @param itr  the number of iterations to apply
	 * @return the rewritten symbol list
	 */
	public List generate(List init, int itr) {
		if (itr == 0) return init;
		
		List l = new ArrayList();
		
		for (int i = 0; i < init.size(); i++) {
			Object o = init.get(i);
			
			if (this.rules.containsKey(o)) {
				Object[] r;
				
				if (this.rules.get(o) instanceof Statement) {
					r = ((Statement) this.rules.get(o)).evaluate(o);
				} else {
					r = (Object[]) this.rules.get(o);
				}

				Collections.addAll(l, r);
			} else {
				l.add(o);
			}
		}
		
		return this.generate(l, --itr);
	}
	
	/**
	 * Interprets a symbol sequence as a turtle-graphics program and builds
	 * an array of {@link ShadableSurface} objects placed in 3D space.
	 *
	 * @param data the symbol sequence to interpret
	 * @param d    the initial movement direction vector
	 * @return the array of surfaces placed during interpretation
	 */
	public ShadableSurface[] generate(Object[] data, Vector d) {
		List s = new ArrayList();
		
		double dl = d.length();
		AbstractSurface base = this.factory.next(null);
		Vector p = base.getLocation();
		ArrayDeque pstack = new ArrayDeque(), dstack = new ArrayDeque();
		
		i: for (int i = 0; i < data.length; i++) {
			if (data[i].equals(LSystem.STEP)) {
				p = p.add(d.multiply(dl / d.length()));
			} else if (data[i].equals(LSystem.FORWARD)) {
				TransformMatrix mx = TransformMatrix.createRotateXMatrix(this.forward.getX());
				TransformMatrix my = TransformMatrix.createRotateYMatrix(this.forward.getY());
				TransformMatrix mz = TransformMatrix.createRotateZMatrix(this.forward.getZ());
				
				d = d.clone();

				Producer dp = v(d);

				dp = mx.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = my.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = mz.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				PackedCollection result = (PackedCollection) dp.get().evaluate(); d = new Vector(result.toDouble(0), result.toDouble(1), result.toDouble(2));
				
				continue i;
			} else if (data[i].equals(LSystem.BACKWARD)) {
				TransformMatrix mx = TransformMatrix.createRotateXMatrix(this.backward.getX());
				TransformMatrix my = TransformMatrix.createRotateYMatrix(this.backward.getY());
				TransformMatrix mz = TransformMatrix.createRotateZMatrix(this.backward.getZ());
				
				d = d.clone();

				Producer dp = v(d);
				
				dp = mx.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = my.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = mz.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				PackedCollection result = (PackedCollection) dp.get().evaluate(); d = new Vector(result.toDouble(0), result.toDouble(1), result.toDouble(2));
				
				continue i;
			} else if (data[i].equals(LSystem.LEFT)) {
				TransformMatrix mx = TransformMatrix.createRotateXMatrix(this.left.getX());
				TransformMatrix my = TransformMatrix.createRotateYMatrix(this.left.getY());
				TransformMatrix mz = TransformMatrix.createRotateZMatrix(this.left.getZ());
				
				d = d.clone();

				Producer dp = v(d);
				
				dp = mx.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = my.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = mz.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				PackedCollection result = (PackedCollection) dp.get().evaluate(); d = new Vector(result.toDouble(0), result.toDouble(1), result.toDouble(2));
				
				continue i;
			} else if (data[i].equals(LSystem.RIGHT)) {
				TransformMatrix mx = TransformMatrix.createRotateXMatrix(this.right.getX());
				TransformMatrix my = TransformMatrix.createRotateYMatrix(this.right.getY());
				TransformMatrix mz = TransformMatrix.createRotateZMatrix(this.right.getZ());
				
				d = d.clone();

				Producer dp = v(d);
				
				dp = mx.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = my.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				dp = mz.transform(dp, TransformMatrix.TRANSFORM_AS_OFFSET);
				PackedCollection result = (PackedCollection) dp.get().evaluate(); d = new Vector(result.toDouble(0), result.toDouble(1), result.toDouble(2));
				
				continue i;
			} else if (data[i].equals(LSystem.PUSH)) {
				pstack.addFirst(p.clone());
				dstack.addFirst(d.clone());
				continue i;
			} else if (data[i].equals(LSystem.POP)) {
				p = (Vector) pstack.removeFirst();
				d = (Vector) dstack.removeFirst();
				continue i;
			} else {
				log("Encountered non-terminal: " + data[i]);
				continue i;
			}
			
			AbstractSurface next = this.factory.next(base);
			next.setLocation(p);
			
			s.add(next);
			base = next;
		}
		
		return (ShadableSurface[]) s.toArray(new ShadableSurface[0]);
	}
	
	/**
	 * Returns a single-character string representation of a symbol sequence for debugging.
	 *
	 * @param data the symbol sequence to print
	 * @return a compact string with one character per symbol
	 */
	public static String print(Object[] data) {
		StringBuilder b = new StringBuilder();
		
		for (int i = 0; i < data.length; i++) {
			if (data[i].equals(LSystem.STEP)) {
				b.append("S");
			} else if (data[i].equals(LSystem.FORWARD)) {
				b.append("F");
			} else if (data[i].equals(LSystem.BACKWARD)) {
				b.append("B");
			} else if (data[i].equals(LSystem.LEFT)) {
				b.append("L");
			} else if (data[i].equals(LSystem.RIGHT)) {
				b.append("R");
			} else if (data[i].equals(LSystem.PUSH)) {
				b.append("[");
			} else if (data[i].equals(LSystem.POP)) {
				b.append("]");
			} else {
				b.append(data[i]);
			}
		}
		
		return b.toString();
	}
}
