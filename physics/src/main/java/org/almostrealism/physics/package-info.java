/**
 * Quantum and classical physics simulation framework.
 * <p>
 * The physics module provides comprehensive models for atomic structure, quantum mechanics,
 * photon field simulation, and classical rigid body dynamics. It enables physically-accurate
 * simulations ranging from electron orbitals to rigid body collisions.
 * </p>
 *
 * <h2>Key Components</h2>
 *
 * <h3>Atomic Structure</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.physics.Atom}</b> - Complete atomic model with electron shells
 *       and quantum mechanical structure</li>
 *   <li><b>{@link org.almostrealism.physics.Shell}</b> - Electron shells (K, L, M, N, etc.) with
 *       orbital configuration</li>
 *   <li><b>{@link org.almostrealism.physics.Orbital}</b> - Quantum orbitals (1s, 2s, 2p, etc.)
 *       with energy levels</li>
 *   <li><b>{@link org.almostrealism.physics.Electron}</b> - Individual electrons with spin and
 *       excitation state</li>
 * </ul>
 *
 * <h3>Photon Fields</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.physics.PhotonField}</b> - Manages photons in 3D space with
 *       propagation and absorption</li>
 *   <li><b>{@link org.almostrealism.physics.Absorber}</b> - Interface for objects that absorb/emit
 *       photons (atoms, surfaces)</li>
 *   <li><b>Photon Propagation</b> - Light transport simulation with direction and energy tracking</li>
 * </ul>
 *
 * <h3>Rigid Body Dynamics</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.physics.RigidBody}</b> - Classical mechanics state management
 *       (position, velocity, acceleration)</li>
 *   <li><b>Inertia Tensors</b> - Moment of inertia calculations for rotational dynamics</li>
 *   <li><b>Force Application</b> - Apply forces, torques, and compute motion</li>
 * </ul>
 *
 * <h3>Physical Constants</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.physics.PhysicalConstants}</b> - Standard physics constants
 *       (c, h, k, etc.)</li>
 *   <li><b>Unit Conversions</b> - Helper methods for unit conversion (eV, nm, etc.)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Atoms</h3>
 * <pre>{@code
 * // Nitrogen atom (7 electrons)
 * Atom nitrogen = new Atom(7, Arrays.asList(
 *     Shell.first(2),        // K shell: 1s^2
 *     Shell.second(2, 3)     // L shell: 2s^2 2p^3
 * ));
 * }</pre>
 *
 * <h3>Photon Field Simulation</h3>
 * <pre>{@code
 * PhotonField field = new DefaultPhotonField();
 *
 * // Add photon (position, direction, energy in eV)
 * field.addPhoton(
 *     new Vector(0, 0, 0),
 *     new Vector(0, 0, 1),
 *     2.5  // 2.5 eV (green light)
 * );
 *
 * // Update field
 * field.tick();
 * }</pre>
 *
 * <h3>Rigid Body Dynamics</h3>
 * <pre>{@code
 * RigidBody body = new RigidBody();
 * body.setMass(10.0);
 * body.setPosition(new Vector(0, 5, 0));
 *
 * // Apply gravity
 * body.addForce(new Vector(0, -9.8 * body.getMass(), 0));
 *
 * // Simulation step
 * double dt = 0.016;  // 60 FPS
 * body.step(dt);
 * }</pre>
 *
 * <h2>Integration with Other Modules</h2>
 * <ul>
 *   <li><b>ar-chemistry</b> - Uses Atom class for element construction</li>
 *   <li><b>ar-space</b> - RigidBody surfaces for physics-enabled rendering</li>
 *   <li><b>ar-algebra</b> - Vector and matrix operations for physics calculations</li>
 * </ul>
 *
 * @see org.almostrealism.physics.Atom
 * @see org.almostrealism.physics.PhotonField
 * @see org.almostrealism.physics.RigidBody
 * @see org.almostrealism.physics.PhysicalConstants
 */
package org.almostrealism.physics;
