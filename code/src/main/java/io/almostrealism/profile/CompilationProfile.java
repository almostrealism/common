/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.profile;

import io.almostrealism.code.OperationMetadata;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class CompilationProfile extends OperationProfile {
	private Map<String, String> operationSources;

	public CompilationProfile() {
		this("default", OperationMetadata::getDisplayName);
	}

	public CompilationProfile(String name, Function<OperationMetadata, String> key) {
		super(name, key);
		this.operationSources = new HashMap<>();
	}

	public void setOperationSources(Map<String, String> operationSources) {
		this.operationSources = operationSources;
	}

	public Map<String, String> getOperationSources() {
		return operationSources;
	}

	public void recordCompilation(OperationMetadata metadata, String code, long nanos) {
		this.operationSources.put(metadata.getDisplayName(), code);
		super.recordDuration(metadata, nanos);
	}
}
