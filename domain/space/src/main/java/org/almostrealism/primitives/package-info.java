/*
 * Copyright 2025 Michael Murray
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
 * Concrete 3D primitive shapes and camera models for scene construction.
 *
 * <p>This package provides ready-to-use geometric primitives that implement
 * {@link org.almostrealism.space.AbstractSurface}, including spheres, cones,
 * cylinders, CSG (Constructive Solid Geometry) objects, and infinite planes.
 * It also contains camera and light-source primitives such as pinhole cameras,
 * absorption planes, rectangular lights, spherical lights, and point-light grids.</p>
 *
 * <p>For the photon-absorber simulation it provides the volumes
 * {@link org.almostrealism.primitives.SphericalVolume} and
 * {@link org.almostrealism.primitives.BoxVolume}, alongside {@link org.almostrealism.primitives.Plane},
 * and {@link org.almostrealism.primitives.SpecularAbsorber}, which re-emits absorbed photons in
 * directions chosen by a BRDF from {@link org.almostrealism.space.distribution}.</p>
 *
 * <p>Rigid-body variants ({@link org.almostrealism.primitives.RigidSphere},
 * {@link org.almostrealism.primitives.RigidPlane}) extend the basic shapes
 * with physics simulation support via {@link org.almostrealism.physics.RigidBody}.</p>
 */
package org.almostrealism.primitives;
