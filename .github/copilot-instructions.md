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

## Key Guidelines
1. Follow Java best practices and idiomatic patterns.
2. Maintain existing code structure and organization.
3. Do not introduce excessive comments, such as explaining each step of a process.
4. Write unit tests using junit for new functionality.
5. Do not include complex messages in Exceptions. Use a single, simple sentence.
   If there is a strong need to report the value of certain fields in an Exception, create a
   custom Exception class that tracks those values separately from the message.