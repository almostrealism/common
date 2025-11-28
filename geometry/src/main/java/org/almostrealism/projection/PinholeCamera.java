/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.ModelEntity;

import org.almostrealism.collect.computations.DynamicCollectionProducer;
import io.almostrealism.collect.TraversalPolicy;

/**
 * A PinholeCamera object represents a camera in 3D. A PinholeCamera object stores the
 * location, viewing direction, up direction, focal length, and projection dimensions
 * which are used for rendering. When constructing a PinholeCamera object you must make
 * these specifications carefully. The camera location is, as expected, the location
 * from which the camera views, represented as a vector. This value is by default at
 * the origin. The viewing direction is a vector that represents the direction the
 * camera is viewing. This value is by default aligned to the positive z axis, or
 * (0.0, 0.0, 1.0). The up direction is a vector that represents the orientation of
 * the camera's "up." This value is by default aligned with the positive y axis or
 * (0.0, 1.0, 0.0). The focal length of the camera can be thought of as the distance
 * from the camera location to the projection. The focal length is also the tangent of
 * half the vertical field of view. The projection dimensions are the dimensions of
 * the projection that the camera will produce. By default the projection dimensions
 * are set to 0.36 by 0.24 to produce a 35mm film aspect ratio.
 * A Camera object also stores three perpendicular vectors that describe a coordinate
 * system. This is the camera coordinate system and is used for projection. These
 * vectors are computed and updated automatically based on the viewing direction and
 * up direction vectors.
 * 
 * @author  Michael Murray
 */
@ModelEntity
public class PinholeCamera extends OrthographicCamera implements ProjectionFeatures {
	public static boolean enableHardwareAcceleration = true;

  	private double focalLength = 1.0;
  	private double blur = 0.0;

	/** Constructs a {@link PinholeCamera} with all default values as described above. */
	public PinholeCamera() {
		super();
		
		super.setProjectionDimensions(0.36, 0.24);
		this.setFocalLength(0.1);
	}
	
	/**
	 * Constructs a {@link PinholeCamera} with the specified location, viewing direction,
	 * and up direction, but with default focal length and projection dimensions as specified above.
	 */
	public PinholeCamera(Vector location, Vector viewDirection, Vector upDirection) {
		super(location, viewDirection, upDirection);
		
		super.setProjectionDimensions(0.36, 0.24);
		this.setFocalLength(0.1);
	}
	
	/**
	 * Constructs a PinholeCamera object with the specified location, viewing direction, up direction,
	 * focal length, and projection dimensions.
	 */
	public PinholeCamera(Vector location, Vector viewDirection, Vector upDirection,
			double focalLength, double projectionX, double projectionY) {
		super(location, viewDirection, upDirection, projectionX, projectionY);
		
		this.setFocalLength(focalLength);
	}
	
	/**
	 * Constructs a PinholeCamera object with the specified location, viewing direction, up direction,
	 * and focal length. Projection dimensions are determined using the specified fields of view.
	 * 
	 * @param location  Camera location.
	 * @param viewDirection  Camera viewing direction.
	 * @param upDirection  Camera up direction.
	 * @param focalLength  Camera focal length.
	 * @param fov  Camera fields of view (radians) {horizontal FOV, vertical FOV}.
	 */
	public PinholeCamera(Vector location, Vector viewDirection, Vector upDirection,
	        double focalLength, double fov[]) {
	    if (fov.length < 2) throw new IllegalArgumentException("Illegal argument: Wrong size array.");

//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("Constructing new camera (" + this.toString() + ")...");
//		}
		
		this.setLocation(location);
		this.setViewingDirection(viewDirection);
		this.setUpDirection(upDirection);
		
		this.setFocalLength(focalLength);
		this.setProjectionDimensions(2 * focalLength * Math.tan(fov[0] / 2), 2 * focalLength * Math.tan(fov[1] / 2));
	}
	
	/** Sets the focal length of this {@link PinholeCamera} to the specified focal length. */
	public void setFocalLength(double focalLength) { this.focalLength = focalLength; }
	
	/** Returns the focal length of this {@link PinholeCamera} as a double value. */
	public double getFocalLength() { return this.focalLength; }
	
	/** @return  { Horizontal FOV, Vertical FOV }, measured in radians. */
	public double[] getFOV() {
		return new double [] { getHorizontalFOV(), getVerticalFOV() };
	}

	public double getHorizontalFOV() {
		return 2.0 * Math.atan(super.getProjectionWidth() / (2.0 * this.focalLength));
	}

	/**
	 * Assigns the projection height based on the specified fov and the focal length of
	 * the camera, then updates the projection width to preserve the aspect ratio.
	 *
	 * @param fov  Vertical field of view.
	 */
	public void setVerticalFOV(double fov) {
		double aspect = getAspectRatio();
		setProjectionHeight(2 * focalLength * Math.tan(fov / 2));
		setProjectionWidth(aspect * getProjectionHeight());
	}

	public double getVerticalFOV() {
		return 2.0 * Math.atan(super.getProjectionHeight() / (2.0 * this.focalLength));
	}
	
	public void setBlur(double blur) { this.blur = blur; }
	
	public double getBlur() { return this.blur; }
	
	/**
	 * Returns a {@link Ray} that represents a line of sight from the camera represented by this {@link PinholeCamera}.
	 * The first two parameters are the coordinates across the camera. These coordinates correspond to the pixels on
	 * the rendered image. The second pair of parameters specify the total integer width and height of the screen.
	 * Although the pixels on the screen must be in integer coordinates, this method provides the ability to create
	 * super high resolution images by allowing you to devote a single pixel to only a fraction of the theoretical
	 * camera surface. This effect can be used to produce large images from small scenes while retaining accuracy.
	 */
	@Override
	public CollectionProducer rayAt(Producer<?> posP, Producer<?> sdP) {
//		if (Settings.produceOutput && Settings.produceCameraOutput) {
//			Settings.cameraOut.println("CAMERA: U = " + this.u.toString() + ", V = " + this.v.toString() + ", W = " + this.w.toString());
//		}

		if (enableHardwareAcceleration) {
			return rayAt(posP, sdP, getLocation(), getProjectionDimensions(),
											blur, focalLength, u, v, w);
		} else {
			return new DynamicCollectionProducer(new TraversalPolicy(6), args -> {
					Pair pos = new Pair((PackedCollection) posP.get().evaluate(args), 0);
					Pair screenDim = new Pair((PackedCollection) sdP.get().evaluate(args), 0);

					double au = -(getProjectionWidth() / 2);
					double av = -(getProjectionHeight() / 2);
					double bu = getProjectionWidth() / 2;
					double bv = getProjectionHeight() / 2;

					Vector p = u.multiply((au + (bu - au) * (pos.getX() / (screenDim.getX() - 1))));
					Vector q = v.multiply((av + (bv - av) * (pos.getY() / (screenDim.getY() - 1))));
					Vector r = w.multiply(-focalLength);

					Vector rayDirection = p;
					rayDirection.addTo(q);
					rayDirection.addTo(r);

					double l = rayDirection.length();

					if (blur != 0.0) {
						double a = blur * (-0.5 + Math.random());
						double b = blur * (-0.5 + Math.random());

						Vector u, v, w = (Vector) rayDirection.clone();

						Vector t = (Vector) rayDirection.clone();

						if (t.getX() < t.getY() && t.getY() < t.getZ()) {
							t.setX(1.0);
						} else if (t.getY() < t.getX() && t.getY() < t.getZ()) {
							t.setY(1.0);
						} else {
							t.setZ(1.0);
						}

						w.divideBy(w.length());

						u = t.crossProduct(w);
						u.divideBy(u.length());

						v = w.crossProduct(u);

						rayDirection.addTo(u.multiply(a));
						rayDirection.addTo(v.multiply(b));
						rayDirection.multiplyBy(l / rayDirection.length());
					}

					Ray ray = new Ray(getLocation(), rayDirection);

//					if (Settings.produceOutput && Settings.produceCameraOutput) {
//						Settings.cameraOut.println("CAMERA (" + this.toString() + ") : Ray at (" + pos + ", " + screenDim + ") = " + ray.toString());
//					}

					return ray;
				});
		}
	}
	
	public String toString() {
		return "PinholeCamera - " +
				getLocation() + " " +
				getViewDirection() + " " +
				getProjectionWidth() + "x" +
				getProjectionHeight();
	}
}

