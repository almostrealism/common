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
 * HTTP REST API for the FlowTree controller.
 *
 * <p>{@link io.flowtree.api.FlowTreeApiEndpoint} is the NanoHTTPD-based router
 * that dispatches to focused per-resource handlers:</p>
 * <ul>
 *   <li>{@link io.flowtree.api.WorkstreamRegistrationHandler} &mdash; register
 *       a new workstream from a repo / branch.</li>
 *   <li>{@link io.flowtree.api.WorkstreamLifecycleHandler} &mdash; archive /
 *       unarchive / delete an existing workstream.</li>
 *   <li>{@link io.flowtree.api.WorkspaceConfigHandler} &mdash; read / update
 *       workspace-level config (including the unified Phase config bundle).</li>
 *   <li>{@link io.flowtree.api.AgentsQueryHandler} &mdash; list connected
 *       agents and the workstreams they serve.</li>
 *   <li>{@link io.flowtree.api.StatsQueryHandler} &mdash; aggregated job
 *       statistics for the {@code /stats} command.</li>
 *   <li>{@link io.flowtree.api.SecretsRequestHandler} &mdash; proxies signed
 *       secret requests from running jobs to ar-manager.</li>
 *   <li>{@link io.flowtree.api.MessageEndpointHandler} &mdash; pipes
 *       per-workstream messages into ar-memory.</li>
 *   <li>{@link io.flowtree.api.AutoPrContext} &mdash; bookkeeping for the
 *       auto-create-PR-on-success behaviour.</li>
 * </ul>
 *
 * <p>This package is HTTP-shape only. The model lives in
 * {@code io.flowtree.workstream}; the controller wiring in
 * {@code io.flowtree.controller}; submission-time runner / Phase config
 * resolution in {@code io.flowtree.submission}.</p>
 */
package io.flowtree.api;
