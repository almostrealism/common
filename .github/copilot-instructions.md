# Agent Instructions

## Overview
This repository provides data structures in Java for operations in algebra, geometry, and
other mathematics along with datatypes for both video and audio that are useful in both
scientific computations and the automated production of artwork. These libraries provide
abstractions that can be used at runtime with a whole range of different acceleration
strategies, so the developer does not have to make a commitment to a particular strategy
for production use of your model code ahead of time.

PLEASE suggest improvements to this document based on any PR comments you observe,
and make sure to update relevant agent memory files when you need to remember information
for future sessions.

## Repository Structure
- This repository is organized according to the standard Maven directory layout.
- All tests should be located in the `utils` module (except for those that depend on code in the
  `ml` module, which can remain in the `ml` module), because this module includes many helpful
  utilities for testing across the data types from throughout the repository.


## Development Standards and Process

Do NOT consider any work complete until you CONFIRM that the pipeline will pass
(see .github/workflows/analysis.yml for details if you are unsure of the process).

### Build and Package

To confirm that the code compiles, you can run the build process using mvn.
You may not be able to figure out how to get every test to pass, but you should
at least ensure that the code compiles before making a commit.

```shell
mvn package
```

### Testing
It may be okay for some tests to fail, because not all tests pass on all hardware platforms.
However, all tests that run as part of the CI/CD pipeline MUST pass before a PR can be merged.

To confirm that the tests pass, you can run the test suite using mvn.

```shell
LD_LIBRARY_PATH=Extensions mvn test \
  -DAR_HARDWARE_DRIVER=native \
  -DAR_HARDWARE_MEMORY_SCALE=7 \
  -DAR_HARDWARE_LIBS=Extensions \
  -DAR_TEST_PROFILE=pipeline
```


## Agent Memory
- Every module contains a file named `agent-memory.md` that can be used to keep track of
  information that seems relevant to agents working on code in that module.
- Whenever you receive instructions from PR comments for changes, or when you notice something
  that you want to remember for future session, make sure to include modifications to the memory
  file in the relevant module(s).
- DO NOT use the agent memory file to simple record the same information that is already available
  in the javadoc documentation for the module; agent memory is for general concepts that are useful
  to remember during development, NOT regurgitation of the documentation in markdown form.


## Guidance
Follow Java best practices and idiomatic patterns, while maintaining the existing code structure and organization.

### Key Principles
1. Do not introduce excessive comments, such as explaining each step of a process.
2. Always review the `agent-memory.md` file in the module you plan to modify before making changes (see above).
3. Write unit tests using junit for new functionality.
4. Do not include complex messages in Exceptions. Use a single, simple sentence.
   If there is a strong need to report the value of certain fields in an Exception, create a
   custom Exception class that tracks those values separately from the message.

### Documentation
1. Always refer to types referenced in javadoc documentation using their formal class name
   along with `@link` so that the javadoc generator can link to the class.
2. Always make sure that @param, @throws, and @return macros appear LAST in the javadoc
   documentation for a method, in that order; do NOT put examples or other text after these.