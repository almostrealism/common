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

package org.almostrealism.hardware.cl;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.concurrent.Semaphore;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.cl_event;

import java.util.function.Consumer;

public class CLSemaphore implements Semaphore {
	private OperationMetadata requester;
	private CLComputeContext context;
	private cl_event event;
	private Consumer<RunData> profile;

	public CLSemaphore(OperationMetadata requester, CLComputeContext context,
					   cl_event event, Consumer<RunData> profile) {
		this.requester = requester;
		this.context = context;
		this.event = event;
		this.profile = profile;
	}

	public cl_event getEvent() { return event; }

	@Override
	public OperationMetadata getRequester() { return requester; }

	@Override
	public void waitFor() { context.processEvent(event, profile); }

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new CLSemaphore(requester, context, event, profile);
	}
}
