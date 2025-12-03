/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.projection;

import io.almostrealism.relation.Producer;
import io.almostrealism.uml.ModelEntity;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.io.DecodePostProcessing;

/**
 * The {@link OrthographicCamera} provides an orthographic projection camera.
 * The camera location is, as expected, the location from which the camera
 * views, represented as a vector. This value is by default at the origin.
 * The viewing direction is a vector that represents the direction the camera
 * is viewing. This value is by default aligned to the positive Z axis, or
 * (0.0, 0.0, 1.0). The up direction is a vector that represents the
 * orientation of the camera's "up." This value is by default aligned with
 * the positive y axis or (0.0, 1.0, 0.0). The projection dimensions of the
 * camera are the dimensions of the viewing plane.
 * 
 * @author  Michael Murray
 */
@ModelEntity
public class OrthographicCamera implements Camera, Positioned, DecodePostProcessing, ScalarFeatures, PairFeatures, VectorFeatures, RayFeatures {
	private Vector location = new Vector(0.0, 0.0, 0.0);
	private Vector viewDirection = new Vector(0.0, 0.0, 1.0);
	private Vector upDirection = new Vector(0.0, 1.0, 0.0);
	private final Pair projectionDimensions = new Pair();
  
	protected Vector u, v, w;
  
	/**
	 * Constructs a new OrthographicCamera object with the defaults described above.
	 */
	public OrthographicCamera() {
		this.setLocation(new Vector(0.0, 0.0, 0.0));
		this.setViewingDirection(new Vector(0.0, 0.0, 1.0));
		this.setUpDirection(new Vector(0.0, 1.0, 0.0));
		
		this.setProjectionDimensions(3.5, 2.0);
	}
	
	/**
	 * Constructs an OrthographicCamera object with the specified location, viewing direction,
	 * and up direction, but with default projection dimensions as specified above.
	 * 
	 * @param location  Camera location.
	 * @param viewDirection  Camera viewing direction.
	 * @param upDirection  Camera up direction.
	 */
	public OrthographicCamera(Vector location, Vector viewDirection, Vector upDirection) {
		this.setLocation(location);
		this.setViewingDirection(viewDirection);
		this.setUpDirection(upDirection);
		
		this.setProjectionDimensions(3.5, 2.0);
	}
	
	public OrthographicCamera(Vector location, Vector viewDirection, Vector upDirection,
							double projectionX, double projectionY) {
		this.setLocation(location);
		this.setViewingDirection(viewDirection);
		this.setUpDirection(upDirection);
		
		this.setProjectionDimensions(projectionX, projectionY);
	}
	
	/**
	 * Sets the location of this OrthographicCamera object to the specified location.
	 */
	public void setLocation(Vector location) { this.location = location; }
	
	/** Calls the setViewingDirection() method. */
	public void setViewDirection(Vector viewDirection) { this.setViewingDirection(viewDirection); }
	
	/**
	 * Sets the viewing direction of this PinholeCamera object to the specified viewing direction.
	 * This method automatically updates the camera coordinate system vectors.
	 */
	public void setViewingDirection(Vector viewDirection) {
		this.viewDirection = viewDirection;
		
		this.updateUVW();
	}
	
	/**
	 * Sets the up direction of this PinholeCamera object to the specified up direction.
	 * This method automatically updates the camera coordinate system vectors.
	 */
	public void setUpDirection(Vector upDirection) {
		this.upDirection = upDirection;
		
		this.updateUVW();
	}

	/**
	 * Sets the projection dimensions to the specified projection dimensions.
	 */
	public void setProjectionDimensions(double projectionX, double projectionY) {
		this.projectionDimensions.setX(projectionX);
		this.projectionDimensions.setY(projectionY);
	}

	public Pair getProjectionDimensions() { return projectionDimensions; }
	
	/**
	 * Sets the projection width of this OrthographicCamera object to the specified projection width.
	 */
	public void setProjectionWidth(double projectionX) {
		this.projectionDimensions.setX(projectionX);
	}
	
	/**
	 * Sets the projection height of this {@link OrthographicCamera} to the specified height.
	 */
	public void setProjectionHeight(double projectionY) {
		this.projectionDimensions.setY(projectionY);
	}

	/** Returns the ratio of the projection width of this camera to the projection height of this camera. */
	public double getAspectRatio() { return getProjectionWidth() / getProjectionHeight(); }

	/** Updates the orthonormal vectors used to describe camera space for this {@link OrthographicCamera}. */
	public void updateUVW() {
		this.w = this.viewDirection.divide(this.viewDirection.length()).minus();
		this.u = this.upDirection.crossProduct(this.w);
		this.u.divideBy(this.u.length());
		this.v = this.w.crossProduct(this.u);
	}
	
	@Override
	public void setPosition(float x, float y, float z) { setLocation(new Vector(x, y, z)); }
	
	@Override
	public float[] getPosition() { return getLocation().toFloat(); }
	
	/** Returns the location of this PinholeCamera object as a Vector object. */
	public Vector getLocation() { return this.location; }
	
	/** Calls the {@link #getViewingDirection()} method and returns the result. */
	public Vector getViewDirection() { return this.getViewingDirection(); }
	
	/** Returns the viewing direction of this {@link OrthographicCamera} object as a {@link Vector} object. */
	public Vector getViewingDirection() { return this.viewDirection; }
	
	/**
	 * Returns the up direction of this {@link OrthographicCamera} as a {@link Vector} object.
	 */
	public Vector getUpDirection() { return this.upDirection; }
	
	/** Returns the projection width of this {@link OrthographicCamera} object as a double value. */
	public double getProjectionWidth() { return projectionDimensions.getX(); }
	
	/** Returns the projection height of this {@link OrthographicCamera} object as a double value. */
	public double getProjectionHeight() { return projectionDimensions.getY(); }
	
	/**
	 * @return  A {@link TransformMatrix} object that can be used to convert coordinates in the
	 *          coordinate system described by this {@link Camera} to the standard x, y, z coordinates.
	 */
	public TransformMatrix getRotationMatrix() {
	    double[][] matrix = {{this.u.getX(), this.u.getY(), this.u.getZ()},
	            				{this.v.getX(), this.v.getY(), this.v.getZ()},
	            				{this.w.getX(), this.w.getY(), this.w.getZ()}};
	    
	    return new TransformMatrix(matrix);
	}

	/** @see Camera#rayAt(Producer, Producer) */
	@Override
	public CollectionProducer rayAt(Producer<?> pos, Producer<?> sd) {
		CollectionProducer p = divide((Producer) pos, (Producer) sd).subtract(pair(0.5, 0.5)).multiply(v(getProjectionDimensions()));
		CollectionProducer xy = vector(l(p), r(p), c(0.0));
		Producer<PackedCollection> o = getRotationMatrix().getInverse()
				.transform(xy, TransformMatrix.TRANSFORM_AS_LOCATION);
		return ray(o, v(viewDirection));
	}

	@Override
	public void afterDecoding() { updateUVW(); }
}
