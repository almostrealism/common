/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.benchmark;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A named, ordered population of {@link WorkloadInstance}s that the benchmark runs as one unit,
 * against an instruction cache holding nothing the scenario did not compile itself.
 */
public class BenchmarkScenario {
	/** The name the scenario is reported under. */
	private final String name;
	/** What the scenario probes. */
	private final String purpose;
	/** The instances, in the order they are run. */
	private final List<WorkloadInstance> instances;

	/**
	 * Creates a scenario.
	 *
	 * @param name      the name the scenario is reported under
	 * @param purpose   what the scenario probes
	 * @param instances the instances, in the order they are run
	 */
	public BenchmarkScenario(String name, String purpose, List<WorkloadInstance> instances) {
		this.name = name;
		this.purpose = purpose;
		this.instances = Collections.unmodifiableList(instances);
	}

	/**
	 * Returns the name the scenario is reported under.
	 *
	 * @return the name
	 */
	public String getName() { return name; }

	/**
	 * Returns what the scenario probes.
	 *
	 * @return the purpose
	 */
	public String getPurpose() { return purpose; }

	/**
	 * Returns the instances, in the order they are run.
	 *
	 * @return the instances
	 */
	public List<WorkloadInstance> getInstances() { return instances; }

	/**
	 * Returns the fraction of instances whose {@link WorkloadInstance#structureKey() structure}
	 * repeats one earlier in the scenario: the hit rate of a cache keyed on exactly the
	 * properties a kernel could depend on, counting one kernel per computation. A measured hit
	 * rate above it shows kernels shared across shapes or layouts; one below it shows reuse the
	 * signatures miss.
	 *
	 * @return the structural repeat rate
	 */
	public double structuralRepeatRate() {
		if (instances.isEmpty()) return 0.0;

		Set<String> seen = new HashSet<>();
		int repeats = 0;
		for (WorkloadInstance instance : instances) {
			if (!seen.add(instance.structureKey())) repeats++;
		}

		return repeats / (double) instances.size();
	}

	/**
	 * Returns the number of distinct templates the scenario's instances bind.
	 *
	 * @return the distinct template count
	 */
	public int distinctTemplates() {
		return (int) instances.stream().map(i -> i.getTemplate().getId()).distinct().count();
	}

	/**
	 * Returns a fingerprint of the scenario's population: equal for two runs exactly when they
	 * generated the same instances in the same order, which is what makes their reports
	 * comparable. It is built from each instance's {@link WorkloadInstance#generationKey()}, so a
	 * change to any generated constant, scalar or data seed changes it.
	 *
	 * @return the population fingerprint
	 */
	public String fingerprint() {
		long hash = 1125899906842597L;
		for (WorkloadInstance instance : instances) {
			hash = 31 * hash + instance.generationKey().hashCode();
		}

		return Long.toUnsignedString(hash, 36);
	}
}
