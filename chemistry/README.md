# Almost Realism Chemistry Module (`ar-chemistry`)

The Chemistry Module provides a complete model of the periodic table and chemical elements. It enables programmatic access to all 118 chemical elements with their atomic structures and electron configurations.

## Purpose

This module exists to:

1. **Model the Periodic Table** - Provides class representations of all chemical elements
2. **Define Atomic Structure** - Encapsulates electron shell configurations for each element
3. **Enable Element Access** - Organized access to elements by groups, periods, and categories
4. **Support Alloy Creation** - Probabilistic mixing of elements for material science applications
5. **Integrate with Physics** - Works with ar-physics module to construct actual Atom objects

## What It Provides

### 1. Complete Periodic Table

Access all 118 chemical elements through the `PeriodicTable` class:

```java
import org.almostrealism.chem.*;
import org.almostrealism.physics.*;

// Individual elements
Element carbon = PeriodicTable.Carbon;
Element gold = PeriodicTable.Gold;
Element oxygen = PeriodicTable.Oxygen;

// Construct atoms with proper electron configuration
Atom carbonAtom = carbon.construct();
System.out.println("Atomic number: " + carbon.getAtomicNumber());  // 6
```

### 2. Organized Element Groups

Access elements by chemical family, period, or group:

```java
// By chemical family
List<Element> alkaliMetals = PeriodicTable.alkaliMetals();     // Li, Na, K, Rb, Cs, Fr
List<Element> nobleGases = PeriodicTable.nobleGasses();        // He, Ne, Ar, Kr, Xe, Rn
List<Element> halogens = PeriodicTable.halogens();             // F, Cl, Br, I, At
List<Element> transitionMetals = PeriodicTable.transitionMetals();

// By orbital block
List<Element> sBlock = PeriodicTable.sBlock();  // Groups 1-2
List<Element> pBlock = PeriodicTable.pBlock();  // Groups 13-18

// By period (row)
List<Element> period1 = PeriodicTable.Periods.first();   // H, He
List<Element> period2 = PeriodicTable.Periods.second();  // Li, Be, B, C, N, O, F, Ne

// By group (column)
List<Element> group1 = PeriodicTable.Groups.first();     // Alkali metals
List<Element> group18 = PeriodicTable.Groups.eighteenth(); // Noble gases

// Special groups
List<Element> lanthanoids = PeriodicTable.lanthanoids(); // Rare earth elements
List<Element> actinoids = PeriodicTable.actinoids();     // Actinide series
List<Element> organics = PeriodicTable.organics();       // Common organic elements
```

### 3. Alloy Creation (Probabilistic Mixtures)

Create mixtures of elements with specified probabilities:

```java
import org.almostrealism.chem.Alloy;

// Bronze: 88% Copper, 12% Tin
Alloy bronze = new Alloy(
    Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin),
    0.88, 0.12
);

// Construct atoms from the alloy (probabilistically selects element)
Atom atom = bronze.construct();

// Steel: Iron with trace elements
Alloy steel = new Alloy(
    Arrays.asList(PeriodicTable.Iron, PeriodicTable.Carbon, PeriodicTable.Manganese),
    0.98, 0.015, 0.005
);
```

## Key Interfaces

### Element
The base interface for all chemical elements:

```java
public interface Element extends Atomic {
    int getAtomicNumber();  // Returns atomic number (1 for H, 6 for C, etc.)
}
```

### Atomic
Interface for objects that can construct atoms:

```java
public interface Atomic extends Factory<Atom>, Substance {
    Atom construct();  // Creates an Atom with proper electron configuration
}
```

### Substance
Base interface for all chemical substances:

```java
public interface Substance extends Node {
    // Marker interface
}
```

## Atomic Structure

Each element encapsulates its electron shell configuration:

```java
// Hydrogen: 1 electron in first shell
public class Hydrogen implements Element {
    public int getAtomicNumber() { return 1; }

    public Atom construct() {
        List<Shell> shells = Arrays.asList(
            new Shell(1)  // 1 electron
        );
        return new Atom(getAtomicNumber(), shells);
    }
}

// Carbon: 2 electrons in first shell, 4 in second shell
public class Carbon implements Element {
    public int getAtomicNumber() { return 6; }

    public Atom construct() {
        List<Shell> shells = new ArrayList<>();
        shells.addAll(Helium.getShells());  // Reuse Helium's structure (2 electrons)
        shells.add(new Shell(4));           // Add 4 electrons in second shell
        return new Atom(getAtomicNumber(), shells);
    }
}
```

## Periodic Table Organization

The `PeriodicTable` class provides multiple ways to access elements:

### By Category
```java
PeriodicTable.metals()              // All metallic elements
PeriodicTable.nonMetals()           // Non-metallic elements
PeriodicTable.mainGroup()           // Main group elements
PeriodicTable.organics()            // C, H, N, O, P, S (common in organic chemistry)
```

### By Family
```java
PeriodicTable.alkaliMetals()        // Group 1 (except H)
PeriodicTable.alkalineEarthMetals() // Group 2
PeriodicTable.transitionMetals()    // Transition elements
PeriodicTable.halogens()            // Group 17
PeriodicTable.nobleGasses()         // Group 18
PeriodicTable.chalcogens()          // Group 16 (O, S, Se, Te, Po)
PeriodicTable.pnictogens()          // Group 15 (N, P, As, Sb, Bi)
```

### By Block
```java
PeriodicTable.sBlock()              // s-orbital block (Groups 1-2)
PeriodicTable.pBlock()              // p-orbital block (Groups 13-18)
PeriodicTable.lanthanoids()         // f-block: Lanthanide series
PeriodicTable.actinoids()           // f-block: Actinide series
```

## Usage Examples

### Example 1: Building Molecules (Conceptual)

```java
// Access elements for organic compounds
Element carbon = PeriodicTable.Carbon;
Element hydrogen = PeriodicTable.Hydrogen;
Element oxygen = PeriodicTable.Oxygen;

// Construct individual atoms
Atom C = carbon.construct();
Atom H = hydrogen.construct();
Atom O = oxygen.construct();

// Use with physics module to build molecular structures
// (Actual bonding and molecular geometry handled by physics module)
```

### Example 2: Filtering Elements

```java
// Find all metals with atomic number > 50
List<Element> heavyMetals = PeriodicTable.metals().stream()
    .filter(e -> e.getAtomicNumber() > 50)
    .collect(Collectors.toList());

// Get organic elements (C, H, N, O, P, S)
List<Element> organicElements = PeriodicTable.organics();
```

### Example 3: Material Science

```java
// Create common alloys
Alloy brass = new Alloy(
    Arrays.asList(PeriodicTable.Copper, PeriodicTable.Zinc),
    0.70, 0.30  // 70% Cu, 30% Zn
);

Alloy solder = new Alloy(
    Arrays.asList(PeriodicTable.Tin, PeriodicTable.Lead),
    0.60, 0.40  // 60% Sn, 40% Pb
);

// Generate atoms from alloy
for (int i = 0; i < 1000; i++) {
    Atom atom = brass.construct();  // Randomly selects Cu or Zn based on ratio
    processAtom(atom);
}
```

## Integration with Other Modules

### Physics Module
- Provides **Atom** class for atomic structure
- Provides **Shell** class for electron shells
- Chemistry module constructs physics module's Atom objects

### Heredity Module
- **Alloy** extends `ProbabilisticFactory` for genetic algorithms
- Enables evolution of material compositions

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-physics</artifactId>
    <version>0.72</version>
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-heredity</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-chemistry</artifactId>
    <version>0.72</version>
</dependency>
```

## All 118 Elements

The module provides classes for all elements from Hydrogen (1) to Oganesson (118):

**Period 1**: Hydrogen, Helium
**Period 2**: Lithium, Beryllium, Boron, Carbon, Nitrogen, Oxygen, Fluorine, Neon
**Period 3**: Sodium, Magnesium, Aluminum, Silicon, Phosphorus, Sulfur, Chlorine, Argon
**Period 4**: Potassium through Krypton
**Period 5**: Rubidium through Xenon
**Period 6**: Cesium through Radon (including Lanthanides)
**Period 7**: Francium through Oganesson (including Actinides)

Each element is accessible via `PeriodicTable.[ElementName]`.

## Current Limitations

- **Molecule** interface is defined but minimally implemented
- **Hydrocarbon** class is a stub for future organic chemistry support
- **Material** class is a basic wrapper (minimal functionality)
- No built-in molecular geometry or chemical bonding (handled by physics module)

## Further Reading

- See **ar-physics** module for Atom and Shell implementation details
- See **ar-heredity** module for genetic/evolutionary features
