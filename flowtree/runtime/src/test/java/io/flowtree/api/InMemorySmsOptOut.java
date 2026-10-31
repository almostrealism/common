/*
 * Copyright 2026 Michael Murray
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

package io.flowtree.api;

import org.almostrealism.util.SmsOptOutRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * An in-memory {@link SmsOptOutRegistry} for tests, standing in for the
 * persisted store without a database.
 */
final class InMemorySmsOptOut implements SmsOptOutRegistry {

	/** The set of numbers currently opted out. */
	private final Set<String> optedOut = new HashSet<>();

	@Override
	public boolean isOptedOut(String number) {
		return optedOut.contains(number);
	}

	@Override
	public void optOut(String number) {
		optedOut.add(number);
	}

	@Override
	public void optIn(String number) {
		optedOut.remove(number);
	}
}
