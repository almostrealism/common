/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.primitives;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import io.almostrealism.code.Constant;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.space.DistanceEstimator;
import org.almostrealism.space.Mesh;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.CodeFeatures;
import io.almostrealism.relation.Evaluable;

import java.util.Collection;
import java.util.Collections;

// TODO Add ParticleGroup implementation.

/** A {@link Sphere} represents a primitive sphere in 3d space. */
public class Sphere extends AbstractSurface implements DistanceEstimator, CodeFeatures {
	public static boolean enableTransform = true;
	private static boolean enableHardwareAcceleration = true;

	/** Constructs a {@link Sphere} representing a unit sphere centered at the origin that is black. */
	public Sphere() { }
	
	/**
	 * Constructs a {@link Sphere} representing a sphere with the specified center radius
	 * centered at the origin that is black.
	 */
	public Sphere(double radius) { this(new Vector(0.0, 0.0, 0.0), radius); }
	
	/**
	 * Constructs a Sphere object that represents a sphere with the specified center location
	 * and radius that is black.
	 */
	public Sphere(Vector location, double radius) {
		super(location, radius);
	}
	
	/**
	 * Constructs a Sphere object that represents a sphere with the specified center location, radius,
	 * and color.
	 */
	public Sphere(Vector location, double radius, RGB color) {
		super(location, radius, color);
	}

	@Override
	public Mesh triangulate() {
		Mesh m = super.triangulate();
		
		m.addVector(new Vector(0.0, 1.0, 0.0));
		m.addVector(new Vector(1.0, 0.0, 0.0));
		m.addVector(new Vector(0.0, -1.0, 0.0));
		m.addVector(new Vector(-1.0, 0.0, 0.0));
		m.addVector(new Vector(0.0, 0.0, 1.0));
		m.addVector(new Vector(0.0, 0.0, -1.0));
		
		m.addTriangle(0, 1, 4);
		m.addTriangle(1, 2, 4);
		m.addTriangle(2, 3, 4);
		m.addTriangle(3, 0, 4);
		m.addTriangle(1, 0, 5);
		m.addTriangle(2, 1, 5);
		m.addTriangle(3, 2, 5);
		m.addTriangle(0, 3, 5);
		
		return m;
	}

	@Override
	public double getIndexOfRefraction(Vector p) {
		double s = this.getSize();
		
		if (p.subtract(this.getLocation()).lengthSq() <= s * s + Intersection.e) {
			return super.getIndexOfRefraction();
		} else {
			return 1.0;
		}
	}
	
	/**
	 * Returns a Vector object that represents the vector normal to this sphere at the point represented
	 * by the specified Vector object.
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		Producer<Vector> normal = add(point, v(getLocation()).minus());
		if (getTransform(true) != null) {
			Producer<Vector> fnormal = normal;
			normal = getTransform(true).transform(fnormal, TransformMatrix.TRANSFORM_AS_NORMAL);
		}

		return normal;
	}

	/**
	 * Returns a {@link ShadableIntersection} representing the nearest positive point
	 * along the specified {@link Ray} where intersection between the ray and
	 * this {@link Sphere} occurs.
	 *
	 * <h3>Transform-Based Intersection Strategy</h3>
	 *
	 * <p>This method implements ray-sphere intersection using an <b>inverse transform</b> approach:
	 * instead of transforming the sphere into world space, we transform the ray into the sphere's
	 * local coordinate space where intersection math is simpler.</p>
	 *
	 * <h4>The Process:</h4>
	 * <ol>
	 *   <li><b>World Space:</b> Sphere has transform M (typically translation by location + scale by size)</li>
	 *   <li><b>Inverse Transform:</b> Apply M^-1 to the ray, moving it into "sphere space"</li>
	 *   <li><b>Sphere Space:</b> Sphere is now at origin with radius 1.0 (unit sphere)</li>
	 *   <li><b>Intersection:</b> Compute ray-sphere intersection using the transformed ray</li>
	 *   <li><b>Distance:</b> Return parameter t where intersection occurs</li>
	 * </ol>
	 *
	 * <h4>Ray Direction Length and Distance Calculation:</h4>
	 *
	 * <p>A critical question: <b>Does the ray direction need to be unit length?</b></p>
	 *
	 * <p>The intersection math (lines 154-169) uses the formula:</p>
	 * <pre>
	 * g = D.D  (direction dot direction = squared length of direction)
	 * discriminant = b^2 - g(c - 1)
	 * t = (-b +/- sqrt(discriminant)) / g
	 * </pre>
	 *
	 * <p><b>Key Insight:</b> The formula divides by g = ||D||^2, which automatically compensates
	 * for non-normalized directions:</p>
	 * <ul>
	 *   <li>If ||D|| = 1 (normalized): g = 1, and t is the actual distance in world space</li>
	 *   <li>If ||D|| != 1 (scaled): g = ||D||^2, and t is still mathematically correct but represents
	 *       a parameter value rather than geometric distance</li>
	 * </ul>
	 *
	 * <h4>Transform Effects on Ray Direction:</h4>
	 *
	 * <p>When inverse transform M^-1 is applied to a ray:</p>
	 * <ul>
	 *   <li><b>Translation:</b> Affects origin only, direction unchanged</li>
	 *   <li><b>Scale by factor s:</b> Origin scaled by s, direction scaled by s, length becomes s*||D||</li>
	 *   <li><b>Inverse scale by 1/s:</b> Direction scaled by 1/s, length becomes ||D||/s</li>
	 * </ul>
	 *
	 * <p>Example: Sphere at location (2,0,0) with size 1.0</p>
	 * <ul>
	 *   <li>Transform M: translate(2,0,0) then scale(1,1,1) = just translation</li>
	 *   <li>Inverse M^-1: translate(-2,0,0)</li>
	 *   <li>Ray: origin=(2,0,5), direction=(0,0,-1) [normalized, length 1]</li>
	 *   <li>Transformed ray: origin=(0,0,5), direction=(0,0,-1) [still length 1]</li>
	 *   <li>Intersection: t = 4.0 (sphere extends from z=-1 to z=1, ray hits at z=1)</li>
	 * </ul>
	 *
	 * <p>Example: Sphere at location (0,0,0) with size 2.0</p>
	 * <ul>
	 *   <li>Transform M: scale(2,2,2)</li>
	 *   <li>Inverse M^-1: scale(0.5,0.5,0.5)</li>
	 *   <li>Ray: origin=(0,0,10), direction=(0,0,-1) [normalized, length 1]</li>
	 *   <li>Transformed ray: origin'=(0,0,5), direction'=(0,0,-0.5) [NOT normalized, length 0.5]</li>
	 *   <li>Intersection calculation in sphere space:
	 *     <ul>
	 *       <li>b = O'.D' = (0,0,5).(0,0,-0.5) = -2.5</li>
	 *       <li>c = O'.O' = 25</li>
	 *       <li>g = D'.D' = 0.25</li>
	 *       <li>discriminant = b^2 - g(c-1) = 6.25 - 6 = 0.25</li>
	 *       <li>t = (-b - sqrt(discriminant)) / g = (2.5 - 0.5) / 0.25 = 8</li>
	 *     </ul>
	 *   </li>
	 *   <li>Intersection point in sphere space: origin' + t*direction' = (0,0,5) + 8*(0,0,-0.5) = (0,0,1) OK</li>
	 *   <li>Intersection point in world space: (0,0,1) * 2 = (0,0,2) OK</li>
	 *   <li>World space distance check: ||(0,0,2) - (0,0,10)|| = 8 OK</li>
	 *   <li><b>Result: The returned t=8 IS the correct world-space distance!</b></li>
	 * </ul>
	 *
	 * <h4>Mathematical Correctness of Non-Normalized Directions</h4>
	 *
	 * <p><b>Conclusion:</b> The intersection calculation is mathematically correct even when the
	 * transformed ray direction is NOT normalized. The division by g = ||D||^2 in the quadratic
	 * formula automatically compensates for direction scaling:</p>
	 * <ul>
	 *   <li>Inverse transform scales direction by factor 1/s</li>
	 *   <li>This makes g = (1/s)^2 = 1/s^2</li>
	 *   <li>Division by g multiplies the result by s^2</li>
	 *   <li>Combined effect correctly accounts for the transform</li>
	 *   <li>Returned t represents correct distance in WORLD SPACE, not sphere space</li>
	 * </ul>
	 *
	 * <p><b>Therefore:</b> Ray directions do NOT need to be normalized for correct intersection
	 * results. The math works correctly with scaled directions.</p>
	 */
	@Override
	public ShadableIntersection intersectAt(Producer<Ray> r) {
		TransformMatrix m = getTransform(true);

		Producer<Ray> tr = r;
		if (m != null && enableTransform) tr = m.getInverse().transform(tr);

		final Producer<Ray> fr = tr;

		if (enableHardwareAcceleration) {
			// return new ShadableIntersection(this, r, new SphereIntersectAt(fr));
//			Producer<Scalar> distance = scalar(_lessThan(discriminant(fr), scalar(0.0),
//												scalar(-1.0), closest(t(fr))));
			Producer distance = greaterThan(discriminant(fr), c(0.0),
												closest(t(fr)), c(-1.0));
			return new ShadableIntersection(this, r, distance);
		} else {
			Evaluable<Scalar> s = args -> {
				Ray ray = fr.get().evaluate(args);

				double b = ray.oDotd().evaluate(args).toDouble();
				double c = ray.oDoto().evaluate(args).toDouble();
				double g = ray.dDotd().evaluate(args).toDouble();

				double discriminant = (b * b) - (g) * (c - 1);

				if (discriminant < 0) {
					return new Scalar(-1);
				}

				double discriminantSqrt = Math.sqrt(discriminant);

				double t[] = new double[2];

				t[0] = (-b + discriminantSqrt) / (g);
				t[1] = (-b - discriminantSqrt) / (g);

				Scalar st;

				if (t[0] > 0 && t[1] > 0) {
					if (t[0] < t[1]) {
						st = new Scalar(t[0]);
					} else {
						st = new Scalar(t[1]);
					}
				} else if (t[0] > 0) {
					st = new Scalar(t[0]);
				} else if (t[1] > 0) {
					st = new Scalar(t[1]);
				} else {
					return new Scalar(-1);
					// return null;
				}

				return st;
			};

			return new ShadableIntersection(this, r, () -> s);
		}
	}

	@Override
	public double estimateDistance(Ray r) {
		return r.getOrigin().subtract(getLocation()).length() - getSize();
	}

	@Override
	public Operator<Scalar> get() {
		return new Operator<>() {

			@Override
			public Evaluable<Scalar> get() {
				return args -> new Scalar(getInput().get().evaluate(args).lengthSq());
			}

			@Override
			public Scope<Scalar> getScope(KernelStructureContext context) {
				Scope<Scalar> s = new Scope();

				// TODO  This is not correct
				// s.getVariables().add(assign("scalar", get().evaluate()));
				return s;
			}

			@Override
			public Collection<Process<?, ?>> getChildren() {
				return Collections.emptyList();
			}
		};
	}

	@Override
	public Operator<Scalar> expect() {
		return new Constant<>(new Scalar(1.0));
	}

	@Override
	public BoundingSolid calculateBoundingSolid() {
		Vector c = getLocation();
		double r = getSize();
		return new BoundingSolid(c.getX() - r, c.getX() + r, c.getY() - r,
							c.getY() + r, c.getZ() - r, c.getZ() + r);
	}

	// TODO  Make private
	public Producer<Scalar> discriminant(Producer<Ray> ray) {
		// return oDotd(ray).pow(2.0).add(dDotd(ray).multiply(oDoto(ray).add(-1.0)).multiply(-1));
		return oDotd(ray).pow(2.0).subtract(dDotd(ray).multiply(oDoto(ray).subtract(1.0)));
	}

	// TODO  Make private
	public Producer<Scalar> discriminantSqrt(Producer<Ray> ray) {
		return pow(discriminant(ray), c(0.5));
	}

	// TODO  Make private
	/**
	 * Selects the closest valid intersection distance from a pair of ray-sphere intersection solutions.
	 *
	 * <p>This method implements the logic for choosing which of two potential intersection points
	 * should be used as the final intersection distance. The selection follows these rules:</p>
	 * <ol>
	 *   <li>Return the minimum positive value from the pair</li>
	 *   <li>If both values are negative, return -1.0 to indicate no valid intersection (miss)</li>
	 * </ol>
	 *
	 * @param t A {@link Producer} of {@link Pair} containing two intersection distance solutions
	 * @return A {@link Producer} yielding the minimum positive intersection distance, or -1.0 if no valid intersection
	 */
	public Producer closest(Producer<Pair<?>> t) {
		Producer leftDist = l(t);
		Producer rightDist = r(t);

		// Check if left > 0
		Producer leftPositive = greaterThan((Producer) leftDist, (Producer) c(0.0), c(1.0), c(0.0));
		// Check if right > 0
		Producer rightPositive = greaterThan((Producer) rightDist, (Producer) c(0.0), c(1.0), c(0.0));

		// Check if left < right
		Producer leftLessRight = lessThan((Producer) leftDist, (Producer) rightDist, c(1.0), c(0.0));

		// If both positive: return min(left, right)
		// If only left positive: return left
		// If only right positive: return right
		// If neither positive: return -1.0

		// bothPositive = leftPositive * rightPositive
		Producer bothPositive = multiply(leftPositive, rightPositive);

		// minValue = leftLessRight * left + (1 - leftLessRight) * right
		Producer minValue = add(
			multiply(leftLessRight, leftDist),
			multiply(subtract(c(1.0), leftLessRight), rightDist)
		);

		// onlyLeft = leftPositive * (1 - rightPositive)
		Producer onlyLeft = multiply(leftPositive, subtract(c(1.0), rightPositive));

		// onlyRight = rightPositive * (1 - leftPositive)
		Producer onlyRight = multiply(rightPositive, subtract(c(1.0), leftPositive));

		// result = bothPositive * minValue + onlyLeft * left + onlyRight * right + neitherPositive * (-1.0)
		// neitherPositive = (1 - leftPositive) * (1 - rightPositive)
		Producer neitherPositive = multiply(subtract(c(1.0), leftPositive), subtract(c(1.0), rightPositive));

		return add(
			add(
				multiply(bothPositive, minValue),
				multiply(onlyLeft, leftDist)
			),
			add(
				multiply(onlyRight, rightDist),
				multiply(neitherPositive, c(-1.0))
			)
		);
	}

	private CollectionProducer<Pair<?>> t(Producer<Ray> ray) {
		Producer<Scalar> dS = discriminantSqrt(ray);
		Producer<Scalar> minusODotD = oDotd(ray).minus();
		Producer<Scalar> dDotDInv = dDotd(ray).pow(-1.0);
		return pair((Producer) add(minusODotD, dS).multiply(dDotDInv),
				    (Producer) add(minusODotD, minus(dS)).multiply(dDotDInv));
	}
}
