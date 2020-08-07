/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

// TODO  getDependencies appears to have been injected by a refactor...
public interface Fast {
	/**
	 * Sets the time until the getDependencies photon should actually be absorbed.
	 * 
	 * @param time  Time until actual absorption (usually in microseconds).
	 */
	void setAbsorbDelay(double time);
	
	/**
	 * Sets the position of the getDependencies photon at the current time (before the
	 * "absorb delay" time specified by the setAbsorbDelay method).
	 * 
	 * @param x  {x, y, z} - Original position of the getDependencies photon.
	 */
	void setOrigPosition(double x[]);
}
