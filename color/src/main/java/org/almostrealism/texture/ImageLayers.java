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

package org.almostrealism.texture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * TODO  Add support for blending layers
 * 
 * @author  Michael Murray
 */
public class ImageLayers implements ImageSource, Layered<ImageSource> {
	private HashMap<String, ImageSource> layers;
	
	public ImageLayers() {
		this.layers = new HashMap<String, ImageSource>();
	}
	
	public void addLayer(String name, ImageSource image) {
		layers.put(name, image);
	}
	
	public ImageSource getLayer(String name) { return layers.get(name); }
	
	public void addLayers(ImageLayers l) {
		for (Map.Entry<String, ImageSource> m : l.layers.entrySet()) {
			layers.put(m.getKey(), m.getValue());
		}
	}
	
	public void clear() { layers.clear(); }

	@Override
	public int[] getPixels() { return layers.values().iterator().next().getPixels(); }

	@Override
	public int getWidth() { return layers.values().iterator().next().getWidth(); }

	@Override
	public int getHeight() { return layers.values().iterator().next().getHeight(); }

	@Override
	public boolean isAlpha() { return layers.values().iterator().next().isAlpha(); }

	@Override
	public Iterator<ImageSource> iterator() { return layers.values().iterator(); }
}
