/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Pair;
import io.almostrealism.relation.Producer;

/**
 * An interface for camera implementations that generate viewing rays for rendering.
 * Implementations provide methods for calculating the ray that passes through
 * a specific point on the view plane.
 *
 * <p>The camera transforms 2D screen coordinates into 3D rays that can be used
 * for ray tracing operations. Different implementations provide different
 * projection models (e.g., perspective, orthographic).</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * Camera camera = new PinholeCamera(location, viewDir, upDir);
 * Producer<Ray> ray = camera.rayAt(
 *     pair(pixelX, pixelY),
 *     pair(imageWidth, imageHeight)
 * );
 * }</pre>
 *
 * @author  Michael Murray
 * @see org.almostrealism.projection.PinholeCamera
 * @see org.almostrealism.projection.OrthographicCamera
 */
public interface Camera {
	/**
	 * Generates a viewing ray for the specified screen position.
	 *
	 * <p>The screen position represents the pixel coordinates on the image plane,
	 * while the screen dimensions represent the total size of the image. The
	 * resulting ray originates from the camera position (or parallel for
	 * orthographic cameras) and passes through the specified point on the
	 * view plane.</p>
	 *
	 * @param screenPosition the (x, y) coordinates on the screen, typically in pixels
	 * @param screenDimensions the (width, height) of the screen/image
	 * @return a {@link Producer} that produces a {@link Ray} representing the
	 *         viewing ray from the camera through the specified screen point
	 */
	Producer<Ray> rayAt(Producer<Pair> screenPosition, Producer<Pair> screenDimensions);
}
