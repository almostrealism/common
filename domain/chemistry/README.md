# Almost Realism Chemistry Module (`ar-chemistry`)

The Chemistry Module provides a complete model of the periodic table and chemical elements. It enables programmatic access to all 118 chemical elements with their atomic structures and electron configurations.

## Purpose

This module exists to:

1. **Model the Periodic Table** - Provides enum constants for all 118 chemical elements
2. **Define Atomic Structure** - Encapsulates electron shell configurations for each element
3. **Enable Element Access** - Organized access to elements by groups, periods, and categories
4. **Support Alloy Creation** - Probabilistic mixing of elements for material science applications
5. **Integrate with Physics** - Works with ar-physics module to construct actual Atom objects

## What It Provides

### 1. Complete Periodic Table

Access all 118 chemical elements through the `Element` enum (118 enum constants):

```java
import org.almostrealism.chem.*;
import org.almostrealism.physics.*;

// Individual elements
Element carbon = Element.Carbon;
Element gold = Element.Gold;
Element oxygen = Element.Oxygen;

// Construct atoms with proper electron configuration
Atom carbonAtom = carbon.construct();
System.out.println("Atomic number: " + carbon.getAtomicNumber());  // 6
```

### 2. Organized Element Groups

Access elements by chemical family, period, or group:

```java
// By chemical family
List<Element> alkaliMetals = Element.alkaliMetals();     // Li, Na, K, Rb, Cs, Fr
List<Element> nobleGases = Element.nobleGasses();        // He, Ne, Ar, Kr, Xe, Rn
List<Element> halogens = Element.halogens();             // F, Cl, Br, I, At
List<Element> transitionMetals = Element.transitionMetals();

// By orbital block
List<Element> sBlock = Element.sBlock();  // Groups 1-2
List<Element> pBlock = Element.pBlock();  // Groups 13-18

// By period (row)
List<Element> period1 = Element.Periods.first();   // H, He
List<Element> period2 = Element.Periods.second();  // Li, Be, B, C, N, O, F, Ne

// By group (column)
List<Element> group1 = Element.Groups.first();     // Alkali metals
List<Element> group18 = Element.Groups.eighteenth(); // Noble gases

// Special groups
List<Element> lanthanoids = Element.lanthanoids(); // Rare earth elements
List<Element> actinoids = Element.actinoids();     // Actinide series
List<Element> organics = Element.organics();       // Common organic elements
```

### 3. Alloy Creation (Probabilistic Mixtures)

Create mixtures of elements with specified probabilities:

```java
import org.almostrealism.chem.Alloy;

// Bronze: 88% Copper, 12% Tin
Alloy bronze = new Alloy(
    Arrays.asList(Element.Copper, Element.Tin),
    0.88, 0.12
);

// Construct atoms from the alloy (probabilistically selects element)
Atom atom = bronze.construct();

// Steel: Iron with trace elements
Alloy steel = new Alloy(
    Arrays.asList(Element.Iron, Element.Carbon, Element.Manganese),
    0.98, 0.015, 0.005
);
```

## Key Interfaces

### Element
The enum representing all 118 chemical elements:

```java
public enum Element implements Atomic {
    Hydrogen, Helium, Lithium, /* ... all 118 elements ... */ Oganesson;

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
// Elements are enum constants with electron shell configurations
// Hydrogen: 1 electron in first shell (atomic number 1)
Element hydrogen = Element.Hydrogen;
Atom h = hydrogen.construct();

// Carbon: 2 electrons in first shell, 4 in second shell (atomic number 6)
Element carbon = Element.Carbon;
Atom c = carbon.construct();
```

## Periodic Table Organization

The `Element` enum provides multiple ways to access elements:

### By Category
```java
Element.metals()              // All metallic elements
Element.nonMetals()           // Non-metallic elements
Element.mainGroup()           // Main group elements
Element.organics()            // C, H, N, O, P, S (common in organic chemistry)
```

### By Family
```java
Element.alkaliMetals()        // Group 1 (except H)
Element.alkalineEarthMetals() // Group 2
Element.transitionMetals()    // Transition elements
Element.halogens()            // Group 17
Element.nobleGasses()         // Group 18
Element.chalcogens()          // Group 16 (O, S, Se, Te, Po)
Element.pnictogens()          // Group 15 (N, P, As, Sb, Bi)
```

### By Block
```java
Element.sBlock()              // s-orbital block (Groups 1-2)
Element.pBlock()              // p-orbital block (Groups 13-18)
Element.lanthanoids()         // f-block: Lanthanide series
Element.actinoids()           // f-block: Actinide series
```

## Usage Examples

### Example 1: Building Molecules (Conceptual)

```java
// Access elements for organic compounds
Element carbon = Element.Carbon;
Element hydrogen = Element.Hydrogen;
Element oxygen = Element.Oxygen;

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
List<Element> heavyMetals = Element.metals().stream()
    .filter(e -> e.getAtomicNumber() > 50)
    .collect(Collectors.toList());

// Get organic elements (C, H, N, O, P, S)
List<Element> organicElements = Element.organics();
```

### Example 3: Material Science

```java
// Create common alloys
Alloy brass = new Alloy(
    Arrays.asList(Element.Copper, Element.Zinc),
    0.70, 0.30  // 70% Cu, 30% Zn
);

Alloy solder = new Alloy(
    Arrays.asList(Element.Tin, Element.Lead),
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
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-heredity</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-chemistry</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## All 118 Elements

The module provides enum constants for all elements from Hydrogen (1) to Oganesson (118):

**Period 1**: Hydrogen, Helium
**Period 2**: Lithium, Beryllium, Boron, Carbon, Nitrogen, Oxygen, Fluorine, Neon
**Period 3**: Sodium, Magnesium, Aluminum, Silicon, Phosphorus, Sulfur, Chlorine, Argon
**Period 4**: Potassium through Krypton
**Period 5**: Rubidium through Xenon
**Period 6**: Cesium through Radon (including Lanthanides)
**Period 7**: Francium through Oganesson (including Actinides)

Each element is accessible via `Element.[ElementName]`.

**Note:** Atomic types (Atom, Shell, Orbital, etc.) now live in `org.almostrealism.chem` (moved from physics).

## Current Limitations

- **Molecule** interface is defined but minimally implemented
- **Hydrocarbon** class is a stub for future organic chemistry support
- **Material** class is a basic wrapper (minimal functionality)
- No built-in molecular geometry or chemical bonding (handled by physics module)

## Further Reading

- See **ar-physics** module for Atom and Shell implementation details
- See **ar-heredity** module for genetic/evolutionary features
