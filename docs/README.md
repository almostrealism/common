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

## Contributing

To add or improve documentation:

1. **Module READMEs** - Add/update `/workspace/project/common/{module}/README.md`
2. **Package-info** - Add Javadoc to `package-info.java` files
3. **Tutorials** - Create new HTML files in `tutorials/`
4. **Module Pages** - Create detailed pages in `modules/` (future)

## License

Documentation licensed under Apache License, Version 2.0, same as the framework code.
