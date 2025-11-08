# Almost Realism Framework Documentation

This directory contains the interactive HTML documentation for the Almost Realism framework.

## Viewing the Documentation

Open `index.html` in your web browser:

```bash
open docs/index.html
# or
firefox docs/index.html
# or
chrome docs/index.html
```

## Documentation Structure

```
docs/
├── index.html              # Main documentation homepage
├── css/
│   └── style.css          # Styling for all pages
├── js/
│   └── docs.js            # Interactive features
├── modules/               # Module-specific pages (future)
├── tutorials/             # Step-by-step tutorials
│   ├── 01-vectors-and-operations.html
│   ├── 02-packedcollection-basics.html
│   └── ...
└── README.md              # This file
```

## Generating JavaDoc

To generate complete API documentation with JavaDoc:

```bash
# From the project root
cd /workspace/project/common

# Generate JavaDoc for all modules
mvn javadoc:aggregate

# Output will be in: docs/apidocs/
```

The HTML documentation links to these JavaDoc files. The JavaDoc is configured to output directly to `docs/apidocs/` for seamless integration with the interactive documentation website.

## Features

- **Responsive Design** - Works on desktop and mobile
- **Module Overview** - Browse all 24 framework modules
- **Interactive Tutorials** - Step-by-step guides with code examples
- **API Links** - Direct links to JavaDoc for each module
- **Search** - Find modules and content quickly (coming soon)
- **Code Copy** - One-click code snippet copying

## Module Documentation Status

### Fully Documented (README + Package-info)
- ✅ io - Logging, metrics, lifecycle management
- ✅ stats - Probability distributions and sampling
- ✅ tools - UI tools for profiling
- ✅ time - Time-series and signal processing
- ✅ relation - Producer/Evaluable foundation
- ✅ collect - PackedCollection core data structure
- ✅ algebra - Vector, Matrix, Scalar types
- ✅ utils - Testing framework and utilities
- ✅ physics - Quantum and classical physics simulation

### Partial Documentation
- 🟡 hardware - README exists, needs expansion
- 🟡 code - README exists, needs expansion

### Needs Documentation
- ⬜ graph - Neural network layers
- ⬜ ml - Machine learning models
- ⬜ geometry - Geometric primitives
- ⬜ space - 3D scene management
- ⬜ color - Color representation
- ⬜ chemistry - Chemical elements
- ⬜ heredity - Genetic algorithms
- ⬜ economics - Economic modeling
- ⬜ optimize - Optimization algorithms
- ⬜ render - Rendering pipeline
- ⬜ uml - UML generation
- ⬜ llvm - LLVM integration
- ⬜ ml-script - ML scripting

## Contributing

To add or improve documentation:

1. **Module READMEs** - Add/update `/workspace/project/common/{module}/README.md`
2. **Package-info** - Add Javadoc to `package-info.java` files
3. **Tutorials** - Create new HTML files in `tutorials/`
4. **Module Pages** - Create detailed pages in `modules/` (future)

## License

Documentation licensed under Apache License, Version 2.0, same as the framework code.
