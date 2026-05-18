/*
 * Copyright 2016 Michael Murray
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

/*
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package io.flowtree.behavior;

import io.flowtree.Server;

import java.io.PrintStream;

/**
 * Strategy interface for pluggable server-level behaviors that can be applied
 * to a running {@link Server} instance.
 *
 * <p>Implementations encapsulate a single action to be performed against a
 * server — for example, probing the peer topology or re-balancing connections.
 * Behaviors are invoked interactively from the FlowTree CLI via the
 * {@code ::behave} and {@code ::sbehave} commands in
 * {@link io.flowtree.cli.FlowTreeCliServer}.
 *
 * @author  Mike Murray
 */
public interface ServerBehavior {

	/**
	 * Performs this behavior against the given server, writing any diagnostic
	 * output to the supplied print stream.
	 *
	 * @param s   the {@link Server} on which to act
	 * @param out print stream for diagnostic and status output
	 */
	void behave(Server s, PrintStream out);
}
