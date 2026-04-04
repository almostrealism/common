/*
 * Copyright 2025 Michael Murray
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

/**
 * Concurrency primitives for coordinating asynchronous hardware executions.
 *
 * <p>This package provides lightweight synchronization abstractions used to chain
 * GPU/CPU executions together without blocking the calling thread unnecessarily.</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.concurrent.Semaphore} — a completion token returned
 *       by {@link io.almostrealism.code.Execution#accept} that callers can wait on</li>
 *   <li>{@link io.almostrealism.concurrent.DefaultLatchSemaphore} — a {@link java.util.concurrent.CountDownLatch}-based
 *       implementation of {@code Semaphore}</li>
 * </ul>
 */
package io.almostrealism.concurrent;
