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
 * Hardware operation profiling and timing data collection.
 *
 * <p>This package provides lightweight profiling infrastructure for measuring the
 * execution time of hardware operations. {@link org.almostrealism.hardware.profile.ProfileData}
 * collects a series of {@link org.almostrealism.hardware.profile.RunData} measurements that
 * can be used to analyze CPU vs GPU performance characteristics and detect regressions.</p>
 */
package org.almostrealism.hardware.profile;
