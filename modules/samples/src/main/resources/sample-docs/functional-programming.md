# Functional Programming Concepts

## What is Functional Programming?

Functional programming (FP) is a programming paradigm that treats computation as the evaluation of mathematical functions. It emphasizes immutability, pure functions, and declarative code.

## Core Principles

### Pure Functions

A pure function:
1. Always produces the same output for the same input
2. Has no side effects (no I/O, no mutation, no exceptions)

Pure functions are easier to test, reason about, and compose.

```scala
// Pure function
def add(a: Int, b: Int): Int = a + b

// Impure function (has side effect)
def printAndAdd(a: Int, b: Int): Int = {
  println(s"Adding $a and $b")  // side effect
  a + b
}
```

### Immutability

Immutable data cannot be changed after creation. Instead of modifying data, you create new data structures with the changes applied.

Benefits:
- Thread-safe by default
- Easier to reason about program state
- Enables structural sharing for efficiency

### First-Class Functions

Functions can be:
- Assigned to variables
- Passed as arguments to other functions
- Returned from functions

This enables powerful abstractions like map, filter, and reduce.

### Higher-Order Functions

Functions that take functions as arguments or return functions:

```scala
def twice(f: Int => Int): Int => Int = x => f(f(x))
val addOne = (x: Int) => x + 1
val addTwo = twice(addOne)  // x => x + 2
```

## Common Patterns

### Map

Transform each element in a collection:
```scala
List(1, 2, 3).map(_ * 2)  // List(2, 4, 6)
```

### Filter

Keep elements matching a predicate:
```scala
List(1, 2, 3, 4).filter(_ % 2 == 0)  // List(2, 4)
```

### Fold/Reduce

Combine elements into a single value:
```scala
List(1, 2, 3, 4).foldLeft(0)(_ + _)  // 10
```

### FlatMap

Map and flatten nested structures:
```scala
List(1, 2, 3).flatMap(x => List(x, x * 10))  // List(1, 10, 2, 20, 3, 30)
```

## Algebraic Data Types

ADTs model data as closed sets of possible values:

```scala
sealed trait Option[+A]
case class Some[A](value: A) extends Option[A]
case object None extends Option[Nothing]
```

Combined with pattern matching, ADTs provide exhaustive, type-safe data handling.

## Composition Over Inheritance

FP favors composing small, reusable functions rather than building complex inheritance hierarchies. This leads to more flexible and maintainable code.

## Referential Transparency

An expression is referentially transparent if it can be replaced with its value without changing program behavior. This property enables equational reasoning and optimization.
