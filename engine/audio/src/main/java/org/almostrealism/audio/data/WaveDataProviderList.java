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

import io.almostrealism.lifecycle.Setup;
import org.almostrealism.hardware.OperationList;

import java.util.List;
import java.util.function.Supplier;

/**
 * A list of wave data providers with combined setup operations.
 *
 * @deprecated Use direct collections of WaveDataProvider instances instead.
 */
@Deprecated
public class WaveDataProviderList implements Setup {
	/** Additional setup operations to run before the providers' own setup. */
	private final Supplier<Runnable> setup;

	/** The list of wave data providers managed by this list. */
	private final List<WaveDataProvider> providers;

	/**
	 * Creates a WaveDataProviderList with no additional setup operations.
	 *
	 * @param providers the list of providers to manage
	 */
	public WaveDataProviderList(List<WaveDataProvider> providers) {
		this(providers, new OperationList());
	}

	/**
	 * Creates a WaveDataProviderList with the given providers and setup supplier.
	 *
	 * @param providers the list of providers to manage
	 * @param setup     additional setup operations to run before provider setup
	 */
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

	/**
	 * Returns the list of wave data providers managed by this instance.
	 *
	 * @return the list of providers
	 */
	public List<WaveDataProvider> getProviders() {
		return providers;
	}
}
