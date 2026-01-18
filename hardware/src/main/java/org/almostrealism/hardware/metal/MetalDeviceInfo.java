/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.metal;

/**
 * Device information wrapper for {@link MTLDevice}.
 *
 * @see MTLDevice
 * @see MetalDataContext
 */
public class MetalDeviceInfo {
	private MTLDevice device;

	/**
	 * Creates device information wrapper for a Metal device.
	 *
	 * @param device The {@link MTLDevice} to wrap
	 */
	public MetalDeviceInfo(MTLDevice device) {
		this.device = device;
	}
}
