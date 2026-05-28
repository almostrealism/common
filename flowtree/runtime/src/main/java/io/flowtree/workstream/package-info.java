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

/**
 * Workstream model and persistence.
 *
 * <p>A {@link io.flowtree.workstream.Workstream} represents a logical channel
 * for submitting coding jobs to a FlowTree controller: it carries the target
 * repository, default branch, agent environment, runner / Phase configuration
 * bundle, and any other context that applies to every job submitted into it.
 * {@link io.flowtree.workstream.WorkstreamConfig} is the persistent registry
 * of workstreams and the workspaces that own them, including YAML
 * load/save.</p>
 *
 * <p>This package contains the data model and persistence only. The HTTP
 * endpoints that register / update / list workstreams live in
 * {@code io.flowtree.api}; the controller that wires everything together
 * lives in {@code io.flowtree.controller}; the submission-time resolvers
 * that walk workstream &rarr; workspace &rarr; controller defaults live in
 * {@code io.flowtree.submission}.</p>
 */
package io.flowtree.workstream;
