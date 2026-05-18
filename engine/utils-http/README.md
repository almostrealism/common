# Utils-HTTP Module (ar-utils-http)

HTTP-related infrastructure: authentication and event delivery over HTTP.

## Overview

This module extends ar-utils with HTTP-specific capabilities. It provides authentication interfaces for credential verification and an HTTP-based event delivery mechanism.

## Key Types

### Authentication (`org.almostrealism.auth`)

- **`Login`** — interface for verifying user credentials (`checkPassword(user, password)`)
- **`Authenticatable`** — marker interface for entities that can be authenticated
- **`AuthenticatableFactory<T>`** — factory for creating authenticated instances from credentials

### Event Delivery (`org.almostrealism.event`)

- **`DefaultHttpEventDelivery`** — HTTP-based event delivery implementation

The base event types (`AbstractEvent`, `DefaultEvent`, `EventDeliveryQueue`, `SimpleEventServer`) live in ar-utils; this module adds the HTTP transport layer.

## Dependencies

- **ar-utils** — base utilities and event infrastructure
- **com.fasterxml.jackson.core:jackson-databind** — JSON serialization for HTTP payloads
