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

package org.almostrealism.model;

import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A set of named weight tensors organized by a dotted naming hierarchy, such as the weights of a
 * model checkpoint ({@code model.layers.0.self_attn.q_proj.weight}).
 *
 * <p>Each dotted name is a path: the tensor named {@code model.norm.weight} is read with
 * {@link #get(String)}, and everything under {@code model.layers} is itself a group, read with
 * {@link #group(String)}, whose names omit that prefix. {@link #members()} lists the first segment
 * of every name, which is how a model repeats a construction once per layer of a checkpoint without
 * computing names.</p>
 *
 * <p>This is the contract a model description reads its weights through, independent of where they
 * came from or how they are stored.</p>
 */
public interface WeightGroup {

	/**
	 * Returns the weight with the given name.
	 *
	 * @param key the weight's name within this group
	 * @return the weight, or {@code null} if this group holds none by that name
	 */
	PackedCollection get(String key);

	/**
	 * Returns the weight with the given name, failing when this group holds none. The failure
	 * should help a reader find the intended name, for example by naming the closest names the
	 * group does hold.
	 *
	 * @param key the weight's name within this group
	 * @return the weight
	 * @throws IllegalArgumentException if this group holds no weight named {@code key}
	 */
	PackedCollection require(String key);

	/**
	 * Returns the weights under one name of this group's hierarchy, as a group of their own whose
	 * names omit that name and the dot after it.
	 *
	 * @param name a name, or dotted path of names, within this group
	 * @return the weights under {@code name}; empty if there are none
	 */
	WeightGroup group(String name);

	/**
	 * Returns the distinct first segments of the names in this group, in a stable order. Names
	 * that are all whole numbers, such as the layers of a checkpoint, are in numeric order.
	 *
	 * @return the names at the top of this group's hierarchy
	 */
	List<String> members();

	/**
	 * Returns whether this group holds a weight with the given name.
	 *
	 * @param key the weight's name within this group
	 * @return true if the weight exists
	 */
	boolean containsKey(String key);

	/**
	 * Returns the number of weights in this group.
	 *
	 * @return the weight count
	 */
	int size();
}
