# AR-Physics Module

**Quantum and classical physics simulation framework.**

## Overview

The `ar-physics` module provides:
- **Atomic Structure** - Electrons, orbitals, shells, quantum mechanics
- **Photon Fields** - Light propagation and absorption simulation
- **Rigid Body Dynamics** - Classical mechanics state management
- **Physical Constants** - Standard constants for physics calculations

## Core Components

### Atomic Structure

**Atom** - Atomic model with electron shells:
```java
Atom nitrogen = new Atom(7, Arrays.asList(
    Shell.first(2),        // K shell: 1s²
    Shell.second(2, 3)     // L shell: 2s² 2p³
));
```

**Orbital** - Quantum orbital with energy levels:
```java
Orbital orbital_1s = Orbital.s1;
Orbital orbital_2px = Orbital.p2x;
double energy = orbital.getEnergy();
```

**Electron** - Individual electron with spin and excitation:
```java
Electron e = new Electron(Spin.Up);
e.addExcitationEnergy(excitationEv);
```

### Photon Fields

**PhotonField** - Manages photons in 3D space:
```java
public interface PhotonField {
    void addPhoton(position, direction, energyEv);
    void tick();  // Update photon positions
}
```

**Absorber** - Objects that absorb/emit photons:
```java
public interface Absorber {
    void absorb(Photon photon);
    void emit();
    double getEmitEnergy();
}
```

**Clock** - Time-stepped simulation:
```java
Clock clock = new Clock();
clock.addPhotonField(field);
clock.tick();  // Advance simulation
```

### Rigid Body Dynamics

**RigidBody** - Physics state management:
```java
RigidBody.State state = new RigidBody.State();
state.setPosition(x);
state.setVelocity(v);
state.applyForce(force);
state.update(deltaTime);
```

### Physical Constants

```java
PhysicalConstants.H  // Planck's constant
PhysicalConstants.C  // Speed of light
PhysicalConstants.R  // Rydberg constant
PhysicalConstants.eVtoWatts(ev)
```

## Usage Examples

### Atomic Spectroscopy

```java
// Create hydrogen atom
Atom hydrogen = new Atom(1, List.of(Shell.first(1)));

// Photon absorption
Electrons electrons = hydrogen.getValenceShell().getElectrons();
boolean absorbed = electrons.absorb(photonEnergyEv);
```

### Photon Simulation

```java
PhotonField field = new PhotonFieldImpl();
Absorber camera = new PinholeCameraAbsorber();

// Add photons
field.addPhoton(position, direction, energy);

// Simulate
Clock clock = new Clock();
clock.addPhotonField(field);
for (int i = 0; i < steps; i++) {
    clock.tick();
    camera.absorb(field);
}
```

### Rigid Body Physics

```java
RigidBody.State body = new RigidBody.State();
body.setMass(mass);
body.setPosition(initialPosition);

// Apply forces
body.applyForce(gravity);
body.applyTorque(torque);

// Update
body.update(deltaTime);
Vector newPosition = body.getPosition();
```

## Integration

- **Chemistry Module** - Elements use atomic structure
- **Space Module** - Absorbers for cameras, surfaces
- **Time Module** - Temporal interface for simulation

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-physics</artifactId>
    <version>0.72</version>
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
