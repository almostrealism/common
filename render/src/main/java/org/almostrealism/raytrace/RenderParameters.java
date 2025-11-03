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

package org.almostrealism.raytrace;

import org.almostrealism.algebra.Pair;

import java.util.function.Function;

/**
 * A {@link RenderParameters} instance stores parameters related to the image that will
 * be produced by ray tracing.
 * 
 * @author  Michael Murray
 */
public class RenderParameters {
	public RenderParameters() { }

	public RenderParameters(int w, int h, int ssw, int ssh) {
		this(0, 0, w, h, w, h, ssw, ssh);
	}

	public RenderParameters(int x, int y, int dx, int dy, int w, int h, int ssw, int ssh) {
		this.x = x;
		this.y = y;
		this.dx = dx;
		this.dy = dy;
		this.width = w;
		this.height = h;
		this.ssWidth = ssw;
		this.ssHeight = ssh;
	}
	
	/**  Full image dimensions. */
	public int width, height;
	
	/** Super sample dimensions. */
	public int ssWidth = 2, ssHeight = 2;
	
	/** Coordinates of upper left corner of image. */
	public int x, y;
	
	/** Viewable image dimensions. */
	public int dx, dy;

	public Function<Pair, Pair> positionForIndices() {
		return p -> new Pair(x + p.getX(), height - 1 - p.getY() - y);
	}
}
