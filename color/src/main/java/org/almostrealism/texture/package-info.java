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

/**
 * Texture mapping and image processing utilities for surface detail.
 *
 * <p>This package provides texture support for the Almost Realism rendering pipeline,
 * enabling surfaces to have spatially-varying color properties derived from images
 * or procedural patterns.</p>
 *
 * <h2>Texture Mapping</h2>
 * <ul>
 *   <li>{@link org.almostrealism.texture.Texture} - Base interface for all texture types</li>
 *   <li>{@link org.almostrealism.texture.ImageTexture} - Image-based textures with multiple projection modes</li>
 *   <li>{@link org.almostrealism.texture.StripeTexture} - Procedural stripe pattern generation</li>
 * </ul>
 *
 * <h2>Image I/O</h2>
 * <ul>
 *   <li>{@link org.almostrealism.texture.GraphicsConverter} - Convert between RGB/PackedCollection and AWT types</li>
 *   <li>{@link org.almostrealism.texture.ImageSource} - Abstract image data source</li>
 *   <li>{@link org.almostrealism.texture.URLImageSource} - Load images from URLs</li>
 * </ul>
 *
 * <h2>Image Composition</h2>
 * <ul>
 *   <li>{@link org.almostrealism.texture.ImageLayers} - Stack and composite multiple images</li>
 *   <li>{@link org.almostrealism.texture.ImageCanvas} - Drawing canvas for image manipulation</li>
 *   <li>{@link org.almostrealism.texture.Layered} - Interface for objects supporting layers</li>
 * </ul>
 *
 * <h2>Projection Modes</h2>
 * <p>ImageTexture supports several projection methods:</p>
 * <ul>
 *   <li><b>Spherical</b>: Latitude/longitude mapping for globe-like objects</li>
 *   <li><b>Planar XY/XZ/YZ</b>: Flat projection onto coordinate planes</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Load an image
 * PackedCollection<RGB> image = GraphicsConverter.loadRgb(new File("texture.png"));
 *
 * // Create a texture with spherical projection
 * ImageTexture earth = new ImageTexture(
 *     ImageTexture.SPHERICAL_PROJECTION,
 *     new URL("file:earth.jpg"),
 *     1.0, 1.0,  // Scale
 *     0.0, 0.0   // Offset
 * );
 *
 * // Sample texture at a 3D point
 * RGB color = earth.operate(new Vector(0.5, 0.7, 0.3));
 *
 * // Convert and save
 * BufferedImage output = GraphicsConverter.convertToAWTImage(processedImage, false);
 * ImageIO.write(output, "png", new File("output.png"));
 * }</pre>
 *
 * @see org.almostrealism.color
 * @author Michael Murray
 */
package org.almostrealism.texture;