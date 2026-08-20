# Collection membership is `get(i)`

A `PackedCollection` shaped `[members, size]` already knows how to hand back one
member. Asking for it looks like this:

```java
collection.get(i)
```

It does not look like this:

```java
collection.range(shape(size), i * size)
```

The second form computes, by hand, the offset the collection would have computed
itself. It is not a different operation — it is the same operation, restated, in a
way the reader has to verify arithmetic to understand.

## When the shape is wrong

If the collection is flat and `get(i)` would not give you the member, the shape is
the problem. Fix the shape, then index:

```java
collection.reshape(shape(members, size)).get(i)
```

Reshaping is cheap and says what the data *is*. Reaching for `range()` because the
shape is inconvenient leaves the collection mis-shaped for every later reader, who
then writes their own offset arithmetic against it, and so on.

Where a collection is built rather than received, build it with the right shape in
the first place — `new PackedCollection(shape(members, size))` rather than
`new PackedCollection(members * size)`.

## Never wrap it in an accessor

The failure this rule exists for is not the `range()` call. It is the habit of
promoting it to a named method:

```java
private PackedCollection scalarColumn(int column) {
    return scalars.range(shape(n), column * n);
}
```

That introduces a second vocabulary for membership, local to one class, which every
reader of that class has to learn in order to understand something the collection
type already expresses. Once one such accessor exists, more follow, and membership
stops being a property of collections and becomes a per-class convention.

There is no version of this that is acceptable because the name is good. `rowOf`,
`columnAt`, `memberAt`, `slotFor` are all the same mistake.

## When `range()` is right

`range()` is the correct call whenever the region is not a member:

- a prefix or suffix of a member — `member.range(shape(k))`
- a sliding window — `signal.range(shape(n), 1)` against `signal.range(shape(n), 0)`
- a placement offset that has nothing to do with member boundaries — writing a note
  into the span of an output window where it happens to land

The tell is the offset. `i * size` where `size` is the member size is membership.
An offset that is not a multiple of the member size is a genuine sub-range.
