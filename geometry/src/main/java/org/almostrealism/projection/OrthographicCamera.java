/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.geometry.Camera;
import org.almostrealism.algebra.Pair;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.geometry.Ray;
import org.almostrealism.io.DecodePostProcessing;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.ModelEntity;

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
	private Pair projectionDimensions = new Pair();
  
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
//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA (" + this.toString() + "): Viewing direction vector being set to " + viewDirection.toString());
//		}
		
		this.viewDirection = viewDirection;
		
		this.updateUVW();
	}
	
	/**
	 * Sets the up direction of this PinholeCamera object to the specified up direction.
	 * This method automatically updates the camera coordinate system vectors.
	 */
	public void setUpDirection(Vector upDirection) {
//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA: Up direction vector being set to " + upDirection.toString());
//		}
		
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
//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA: Projection width being set to " + projectionX);
//		}
		
		this.projectionDimensions.setX(projectionX);
	}
	
	/**
	 * Sets the projection height of this {@link OrthographicCamera} to the specified height.
	 */
	public void setProjectionHeight(double projectionY) {
//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA: Projection height being set to " + projectionY);
//		}
		
		this.projectionDimensions.setY(projectionY);
	}

	/** Returns the ratio of the projection width of this camera to the projection height of this camera. */
	public double getAspectRatio() { return getProjectionWidth() / getProjectionHeight(); }

	/** Updates the orthonormal vectors used to describe camera space for this {@link OrthographicCamera}. */
	public void updateUVW() {
		// Calculate camera space vectors using simple math (not Producer-based)
		// to avoid native compilation during camera construction
		this.w = this.viewDirection.divide(this.viewDirection.length()).minus();

		// Manual cross product: upDirection x w
		this.u = manualCrossProduct(this.upDirection, this.w);
		this.u.divideBy(this.u.length());

		// Manual cross product: w x u
		this.v = manualCrossProduct(this.w, this.u);

//		if (Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA: U = " + this.u.toString() + ", V = " + this.v.toString() + ", W = " + this.w.toString());
//		}
	}

	/** Manual cross product calculation to avoid native code compilation during construction. */
	private Vector manualCrossProduct(Vector a, Vector b) {
		double x = a.getY() * b.getZ() - a.getZ() * b.getY();
		double y = a.getZ() * b.getX() - a.getX() * b.getZ();
		double z = a.getX() * b.getY() - a.getY() * b.getX();
		return new Vector(x, y, z);
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
	    double matrix[][] = {{this.u.getX(), this.u.getY(), this.u.getZ()},
	            				{this.v.getX(), this.v.getY(), this.v.getZ()},
	            				{this.w.getX(), this.w.getY(), this.w.getZ()}};
	    
	    return new TransformMatrix(matrix);
	}

	/**
	 * @see org.almostrealism.geometry.Camera#rayAt(io.almostrealism.relation.Producer, io.almostrealism.relation.Producer)
	 */
	@Override
	public Producer<Ray> rayAt(Producer<Pair<?>> pos, Producer<Pair<?>> sd) {
		Producer<Pair<?>> p = divide(pos, sd).subtract(pair(0.5, 0.5)).multiply(v(getProjectionDimensions()));
		Producer<Vector> xy = vector(l(p), r(p), c(0.0));
		Producer<Vector> o = getRotationMatrix().getInverse().transform(xy, TransformMatrix.TRANSFORM_AS_LOCATION);
		return ray(o, v(viewDirection));

//		return new Producer<Ray>() {
//			@Override
//			public Evaluable<Ray> get() {
//				return args -> {
//					Pair p = pos.get().evaluate(args);
//					Pair screenDim = sd.get().evaluate(args);
//
//					double x = getProjectionWidth() * ((p.getX() / screenDim.getX()) - 0.5);
//					double y = getProjectionHeight() * ((p.getY() / screenDim.getY()) - 0.5);
//
//					Vector o = getRotationMatrix().getInverse().transform(vector(x, y, 0.0),
//							TransformMatrix.TRANSFORM_AS_LOCATION).get().evaluate();
//
//					return new Ray(o, viewDirection);
//				};
//			}
//
//			@Override
//			public void compact() {
//				pos.compact();
//				sd.compact();
//			}
//		};
	}

	@Override
	public void afterDecoding() { updateUVW(); }
}
