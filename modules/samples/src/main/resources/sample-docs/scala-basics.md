# Scala Programming Basics

## What is Scala?

Scala is a modern programming language that combines object-oriented and functional programming paradigms. It runs on the Java Virtual Machine (JVM) and is fully interoperable with Java.

## Key Features

### Type Safety

Scala has a strong static type system that catches errors at compile time. The compiler infers types automatically in most cases, reducing boilerplate while maintaining safety.

### Immutability by Default

Scala encourages immutable data structures. Use `val` for immutable values and `var` for mutable variables. Immutability makes code easier to reason about and safer for concurrent programming.

### Pattern Matching

Pattern matching is a powerful feature for decomposing data structures:

```scala
sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Rectangle(width: Double, height: Double) extends Shape

def area(shape: Shape): Double = shape match {
  case Circle(r) => Math.PI * r * r
  case Rectangle(w, h) => w * h
}
```

### Case Classes

Case classes provide immutable data containers with automatic implementations of equals, hashCode, and toString. They are ideal for modeling domain data.

### Higher-Order Functions

Functions are first-class citizens in Scala. You can pass functions as arguments, return them from other functions, and store them in variables.

```scala
val numbers = List(1, 2, 3, 4, 5)
val doubled = numbers.map(_ * 2)  // List(2, 4, 6, 8, 10)
val evens = numbers.filter(_ % 2 == 0)  // List(2, 4)
```

## Collections

Scala provides rich immutable collections:
- `List` - Linked list for sequential access
- `Vector` - Indexed sequence with fast random access
- `Set` - Unordered collection of unique elements
- `Map` - Key-value pairs

## Error Handling

Scala prefers functional error handling over exceptions:
- `Option[A]` - Represents optional values (Some or None)
- `Either[E, A]` - Represents success (Right) or failure (Left)
- `Try[A]` - Captures exceptions as values

## Traits

Traits are similar to interfaces but can contain implementations. They enable multiple inheritance and mixin composition.
