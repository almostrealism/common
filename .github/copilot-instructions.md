This repository provides data structures in Java for operations in algebra, geometry, and
other mathematics along with datatypes for both video and audio that are useful in both
scientific computations and the automated production of artwork. These libraries provide
abstractions that can be used at runtime with a whole range of different acceleration
strategies, so the developer does not have to make a commitment to a particular strategy
for production use of your model code ahead of time.

## Code Standards

PLEASE suggest improvements to this document based on any PR comments you observe.

### Required Before Each Commit
- Make sure that code compiles using mvn clean install.
- It may be okay for some tests to fail, because not all tests pass on all hardware platforms.

### Development Flow
- Build: `mvn package`
- Test: `mvn test`

## Repository Structure
- This repository is organized according to the standard Maven directory layout.

## Agent Memory
- Every module contains a file named `agent-memory.md` that can be used to keep track of
  information that seems relevant to agents working on code in that module.
- Whenever you receive instructions from PR comments for changes, or when you notice something
  that you want to remember for future session, make sure to include modifications to the memory
  file in the relevant module(s).

## Key Guidelines
1. Follow Java best practices and idiomatic patterns.
2. Maintain existing code structure and organization.
3. Do not introduce excessive comments, such as explaining each step of a process.
4. Always review the `agent-memory.md` file in the module you plan to modify before making changes (see above).
5. Write unit tests using junit for new functionality.
6. Do not include complex messages in Exceptions. Use a single, simple sentence.
   If there is a strong need to report the value of certain fields in an Exception, create a
   custom Exception class that tracks those values separately from the message.