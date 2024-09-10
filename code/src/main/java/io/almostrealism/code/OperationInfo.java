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

package io.almostrealism.code;

import io.almostrealism.relation.Process;
import org.almostrealism.io.Describable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public interface OperationInfo extends Describable {
	OperationMetadata getMetadata();

	default List<ComputeRequirement> getComputeRequirements() {
		return null;
	}

	static <T> String name(T value) {
		if (value instanceof OperationInfo) {
			if (((OperationInfo) value).getMetadata() == null) {
				return String.valueOf(value);
			}

			return ((OperationInfo) value).getMetadata().getDisplayName();
		} else {
			return String.valueOf(value);
		}
	}

	static <T> String display(T value) {
		if (value instanceof OperationInfo) {
			return ((OperationInfo) value).getMetadata().getShortDescription();
		} else {
			return String.valueOf(value);
		}
	}

	static <T> OperationMetadata metadataForValue(T value) {
		if (value instanceof OperationInfo) {
			return ((OperationInfo) value).getMetadata();
		} else {
			return null;
		}
	}

	static <P extends Process<?, ?>, T> OperationMetadata metadataForProcess(
						Process<P, T> process, OperationMetadata metadata) {
		List<OperationMetadata> children = process.getChildren().stream()
				.filter(v -> v instanceof OperationInfo)
				.map(v -> ((OperationInfo) v).getMetadata())
				.filter(Objects::nonNull).collect(Collectors.toList());
		return new OperationMetadata(metadata, children);
	}
}
