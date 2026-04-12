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

package org.almostrealism.physics;

import org.almostrealism.texture.IntensityMap;

/**
 * A {@link VolumeAbsorber} that uses separate {@link IntensityMap} instances to
 * control the spatial distribution of photon absorption and emission.
 *
 * @author  Mike Murray
 */
public class IntensityMapAbsorber extends VolumeAbsorber {
	/** Intensity map controlling the probability distribution of emission positions. */
	private IntensityMap emitMap;

	/** Intensity map controlling the probability distribution of absorption positions. */
	private IntensityMap absorbMap;
	
	public void setEmitMap(IntensityMap m) { this.emitMap = m; }
	public IntensityMap getEmitMap() { return this.emitMap; }
	public void setAbsorbMap(IntensityMap m) { this.absorbMap = m; }
	public IntensityMap getAbsorbMap() { return this.absorbMap; }
	
}
