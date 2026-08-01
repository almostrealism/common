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

package org.almostrealism.physics;

import org.almostrealism.color.buffer.AveragedVectorMap2D;
import org.almostrealism.color.buffer.ColorBuffer;

/**
 * Callback interface for updating image buffers during photon field simulation.
 * <p>
 * Implementations receive per-texel update notifications for three buffer types:
 * color (radiance), incidence (incoming light direction), and exitance (outgoing
 * light direction). The {@code front} flag distinguishes between the front and
 * back surfaces of a {@link Volume}.
 * </p>
 *
 * @author  Michael Murray
 */
public interface BufferListener {
	/**
	 * Updates the color buffer for the texel at (u, v) on the specified volume surface.
	 *
	 * @param u       horizontal texture coordinate (0.0 to 1.0)
	 * @param v       vertical texture coordinate (0.0 to 1.0)
	 * @param source  the volume whose surface is being updated
	 * @param target  the color buffer to update
	 * @param front   {@code true} for the front surface, {@code false} for the back surface
	 */
	void updateColorBuffer(double u, double v, Volume<?> source, ColorBuffer target, boolean front);

	/**
	 * Updates the incidence (incoming light direction) buffer for the texel at (u, v).
	 *
	 * @param u       horizontal texture coordinate (0.0 to 1.0)
	 * @param v       vertical texture coordinate (0.0 to 1.0)
	 * @param source  the volume whose surface is being updated
	 * @param target  the averaged vector map to update with the incident direction
	 * @param front   {@code true} for the front surface, {@code false} for the back surface
	 */
	void updateIncidenceBuffer(double u, double v, Volume<?> source, AveragedVectorMap2D target, boolean front);

	/**
	 * Updates the exitance (outgoing light direction) buffer for the texel at (u, v).
	 *
	 * @param u       horizontal texture coordinate (0.0 to 1.0)
	 * @param v       vertical texture coordinate (0.0 to 1.0)
	 * @param source  the volume whose surface is being updated
	 * @param target  the averaged vector map to update with the exitant direction
	 * @param front   {@code true} for the front surface, {@code false} for the back surface
	 */
	void updateExitanceBuffer(double u, double v, Volume<?> source, AveragedVectorMap2D target, boolean front);
}
