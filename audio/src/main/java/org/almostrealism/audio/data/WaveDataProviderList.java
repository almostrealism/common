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

package org.almostrealism.audio.data;

import io.almostrealism.cycle.Setup;
import org.almostrealism.hardware.OperationList;

import java.util.List;
import java.util.function.Supplier;

@Deprecated
public class WaveDataProviderList implements Setup {
	private final Supplier<Runnable> setup;
	private final List<WaveDataProvider> providers;

	public WaveDataProviderList(List<WaveDataProvider> providers) {
		this(providers, new OperationList());
	}

	public WaveDataProviderList(List<WaveDataProvider> providers, Supplier<Runnable> setup) {
		this.providers = providers;
		this.setup = setup;
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList();
		setup.add(this.setup);
		providers.forEach(p -> setup.add(p.setup()));
		return setup;
	}

	public List<WaveDataProvider> getProviders() {
		return providers;
	}
}
