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

import io.almostrealism.uml.ModelEntity;

/**
 * A {@link ThinLensCamera} provides a camera with viewing rays that originate
 * from a random point on a circular lens. By default the width and height of
 * the projection are set to 0.036 by 0.024 and the focal length is set to 0.05.
 * Also, the focus distance is set to 1.0 and the radius of the lens is set to 0.005
 * (default focal length / 10 to produce an f-number of 5).
 * 
 * @author  Michael Murray
 */
@ModelEntity
public class ThinLensCamera extends PinholeCamera {
	private double focalLength, radius, width, height;

	/**
	 * Constructs a new ThinLensCamera object.
	 */
	public ThinLensCamera() {
		super();
		
		this.setProjectionDimensions(0.036, 0.024);
		this.setFocalLength(0.05);
		this.setFocus(1.0);
		this.setLensRadius(this.getFocalLength() / 10);
	}
	
	protected void updateProjectionDimensions() {
		double u = (super.getFocalLength() - this.focalLength) / this.focalLength;
		super.setProjectionDimensions(this.width * u, this.height * u);
	}
	
	/**
	 * Sets the projection dimensions used by this ThinLensCamera object to the specified values.
	 */
	public void setProjectionDimensions(double w, double h) {
		this.width = w;
		this.height = h;
		this.updateProjectionDimensions();
	}
	
	/**
	 * Sets the projection width of this ThinLensCamera object to the specified projection width.
	 */
	public void setProjectionWidth(double w) {
		this.width = w;
		this.updateProjectionDimensions();
	}
	
	/**
	 * Sets the projection height of this ThinLensCamera object to the specified projection height.
	 */
	public void setProjectionHeight(double h) {
		this.height = h;
		this.updateProjectionDimensions();
	}
	
	/**
	 * Returns the projection width of this ThinLensCamera object as a double value.
	 */
	public double getProjectionWidth() { return this.width; }
	
	/**
	 * Returns the projection height of this ThinLensCamera object as a double value.
	 */
	public double getProjectionHeight() { return this.height; }
	
	/**
	 * Sets the distance at which this ThinLensCamera object is focused.
	 * 
	 * @param focus  The focus distance to use.
	 */
	public void setFocus(double focus) {
		super.setFocalLength(focus);
		this.updateProjectionDimensions();
	}
	
	/**
	 * @return  The distance at which this ThinLensCamera object is focused.
	 */
	public double getFocus() { return super.getFocalLength(); }
	
	/**
	 * Sets the focal length used by this ThinLensCamera object.
	 * 
	 * @param focalLength  The focal length value to use.
	 */
	public void setFocalLength(double focalLength) {
		this.focalLength = focalLength;
		this.updateProjectionDimensions();
	}
	
	/**
	 * @return  The focal length value used by this ThinLensCamera object.
	 */
	public double getFocalLength() { return this.focalLength; }
	
	/**
	 * Sets the radius of the lens used by this ThinLensCamera object.
	 * 
	 * @param radius  The radius to use.
	 */
	public void setLensRadius(double radius) { this.radius = radius; }
	
	/**
	 * @return  The radius of the lens used by this ThinLensCamera object.
	 */
	public double getLensRadius() { return this.radius; }
	
	/**
	 * @return  {Horizontal FOV, Vertical FOV} Measured in radians.
	 */
	public double[] getFOV() {
		double u = (super.getFocalLength() - this.focalLength) / this.focalLength;
		
		return new double [] { 2.0 * Math.atan((u * width) / (2.0 * super.getFocalLength())),
								2.0 * Math.atan((u * height) / (2.0 * super.getFocalLength())) };
	}
	
	/**
	 * @return  A ray that is in the same direction as the ray returned
	 * by the PinholeCamera, but with a position that is a random sample
	 * from a disk.
	 */
	/*
	@Override
	public Producer<Ray> rayAt(Producer<Pair> posP, Producer<Pair> sdP) {
		if (enableHardwareAcceleration) {
			Producer<Ray> newRay = new DynamicProducer<>(args -> new Ray());

			return new AcceleratedProducer<>("pinholeCameraRayAt", false,
					new Producer[] { newRay, posP, sdP, new RandomRay() },
					new Object[] { getLocation(), getProjectionDimensions(),
							new Pair(getBlur(), getBlur()),
							new Scalar(focalLength),
							u, v, w});
		} else {
			Producer<Ray> sray = super.rayAt(posP, sdP);

			return new Producer<Ray>() {
				@Override
				public Ray evaluate(Object[] args) {
					Ray ray = sray.evaluate(args);

					double r = Math.random() * radius;
					double theta = Math.random() * Math.PI * 2;
					Vector v = new Vector(r, Math.PI / 2.0, theta, Vector.SPHERICAL_COORDINATES);
					v.addTo(getLocation());

					Vector d = ray.getOrigin();
					d.subtractFrom(v);

					Vector rd = ray.getDirection();
					double rdl = rd.length();

					if (Settings.produceCameraOutput) {
						Settings.cameraOut.println("ThinLensCamera: " + width + " X " + height + " U = " + u);
						Settings.cameraOut.println("ThinLensCamera: Lens sample = " + v);
						Settings.cameraOut.println("ThinLensCamera: Ray origin = " + ray.getOrigin() +
								" direction = " + ray.getDirection());
					}

					ray.setOrigin(v);

					rd.addTo(d);
					rd.multiplyBy(rd.length() / rdl);
					ray.setDirection(rd);

					if (Settings.produceCameraOutput) {
						Settings.cameraOut.println("ThinLensCamera: New ray origin = " + ray.getOrigin() +
								" direction = " + ray.getDirection());
					}

					return ray;
				}

				@Override
				public void compact() {
					// TODO
				}
			};
		}
	}
	*/
}
