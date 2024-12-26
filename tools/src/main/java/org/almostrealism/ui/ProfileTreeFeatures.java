package org.almostrealism.ui;

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationSource;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface ProfileTreeFeatures {
	Comparator<OperationProfileNode> comparator =
			Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed();

	enum TreeStructure {
		ALL,
		COMPILED_ONLY,
		STOP_AT_COMPILED,
		SCOPE_INPUTS;

		List<OperationProfileNodeInfo> children(OperationProfileNodeInfo node, boolean isRoot) {
			if (stop(node, isRoot)) {
				return Collections.emptyList();
			}

			return directChildren(node)
					.flatMap(this::map).collect(Collectors.toList());
		}

		Stream<OperationProfileNodeInfo> directChildren(OperationProfileNodeInfo node) {
			Collection<OperationProfileNode> children = Collections.emptyList();

			if (this == SCOPE_INPUTS) {
				// Try to get the program arguments
				children = node.getProgram()
						.map(OperationSource::getArgumentKeys)
						.stream().flatMap(List::stream)
						.map(node.getRoot()::getProfileNode)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.collect(Collectors.toList());
			}

			if (children.isEmpty()) {
				// If there are no program arguments (or the structure is not
				// expected to retrieve them), get the Process children
				children = node.getNode().getChildren(comparator);
			}

			// Create the OperationProfileNodeInfo instances from any available children
			return children.stream()
					.map(n -> new OperationProfileNodeInfo(node.getRoot(), n));
		}

		Stream<OperationProfileNodeInfo> map(OperationProfileNodeInfo node) {
			if (traverse(node)) {
				return children(node, false).stream();
			}

			return Stream.of(node);
		}

		boolean stop(OperationProfileNodeInfo node, boolean isRoot) {
			if (isRoot) {
				return false;
			} else if (isStopAtCompiled()) {
				return node.isCompiled();
			}

			return node.getNode().getChildren().isEmpty();
		}

		boolean traverse(OperationProfileNodeInfo node) {
			if (this == COMPILED_ONLY) {
				return !node.isCompiled();
			}

			return false;
		}

		boolean isStopAtCompiled() {
			return this == STOP_AT_COMPILED || this == SCOPE_INPUTS;
		}
	}
}
