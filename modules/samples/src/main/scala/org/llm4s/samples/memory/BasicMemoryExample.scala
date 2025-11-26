package org.llm4s.samples.memory

import org.llm4s.agent.memory._

/**
 * Example demonstrating basic memory system usage.
 *
 * This example shows how to:
 * - Create a memory manager
 * - Store different types of memories (user facts, entities, knowledge)
 * - Recall memories with filters
 * - Search memories by keyword
 *
 * No external dependencies required - runs entirely in-memory.
 */
object BasicMemoryExample extends App {
  println("=== Basic Memory System Example ===\n")

  // === Part 1: Creating a Memory Manager ===
  println("1. Creating a Memory Manager")
  println("-" * 40)

  // Start with an empty manager using default in-memory storage
  val manager = SimpleMemoryManager.empty
  println("Created empty SimpleMemoryManager")
  println(s"Initial memory count: ${manager.stats.map(_.totalMemories).getOrElse(0)}")

  // === Part 2: Storing Different Memory Types ===
  println("\n2. Storing Different Memory Types")
  println("-" * 40)

  // Store user preferences
  val withUserFacts = for {
    m1 <- manager.recordUserFact("Prefers Scala over Java", Some("user-123"), Some(0.9))
    m2 <- m1.recordUserFact("Works in fintech industry", Some("user-123"), Some(0.8))
    m3 <- m2.recordUserFact("Based in San Francisco", Some("user-123"), Some(0.7))
  } yield m3

  withUserFacts match {
    case Right(m) =>
      println("Stored 3 user facts")
      m.stats.foreach(s => println(s"  Total memories: ${s.totalMemories}"))
    case Left(e) =>
      println(s"Error: ${e.message}")
  }

  // Store entity information
  val entityId = EntityId.fromName("Scala Programming")
  val withEntities = withUserFacts.flatMap { m =>
    for {
      m1 <- m.recordEntityFact(entityId, "Scala", "Created by Martin Odersky in 2004", "technology", Some(0.9))
      m2 <- m1.recordEntityFact(entityId, "Scala", "Runs on the JVM", "technology", Some(0.8))
      m3 <- m2.recordEntityFact(entityId, "Scala", "Supports both OOP and FP paradigms", "technology", Some(0.9))
    } yield m3
  }

  withEntities match {
    case Right(m) =>
      println("\nStored 3 entity facts about Scala")
      m.stats.foreach(s => println(s"  Total memories: ${s.totalMemories}"))
    case Left(e) =>
      println(s"Error: ${e.message}")
  }

  // Store knowledge from documents
  val withKnowledge = withEntities.flatMap { m =>
    for {
      m1 <- m.recordKnowledge(
        "Functional programming emphasizes immutability and pure functions",
        "docs/fp-intro.md",
        Map("chapter" -> "1", "topic" -> "basics")
      )
      m2 <- m1.recordKnowledge(
        "Scala 3 introduced union types and intersection types",
        "docs/scala3-features.md",
        Map("chapter" -> "2", "topic" -> "types")
      )
    } yield m2
  }

  withKnowledge match {
    case Right(m) =>
      println("\nStored 2 knowledge entries")
      m.stats.foreach(s => println(s"  Total memories: ${s.totalMemories}"))
    case Left(e) =>
      println(s"Error: ${e.message}")
  }

  // === Part 3: Recalling Memories with Filters ===
  println("\n3. Recalling Memories with Filters")
  println("-" * 40)

  val filterExamples = withKnowledge.flatMap { m =>
    for {
      // Get all user facts
      userFacts <- m.store.recall(MemoryFilter.userFacts)
      _ = println(s"\nUser facts (${userFacts.size}):")
      _ = userFacts.foreach(f => println(s"  - ${f.content}"))

      // Get all entity facts
      entityFacts <- m.store.recall(MemoryFilter.entities)
      _ = println(s"\nEntity facts (${entityFacts.size}):")
      _ = entityFacts.foreach(f => println(s"  - ${f.content}"))

      // Get high-importance memories
      important <- m.store.important(threshold = 0.85)
      _ = println(s"\nHigh importance memories (score >= 0.85): ${important.size}")
      _ = important.foreach(f => println(s"  - ${f.content} (importance: ${f.importance.getOrElse(0.0)})"))

      // Combine filters: user facts OR knowledge
      combined <- m.store.recall(MemoryFilter.userFacts || MemoryFilter.knowledge)
      _ = println(s"\nUser facts OR knowledge: ${combined.size} memories")
    } yield m
  }

  // === Part 4: Searching Memories ===
  println("\n4. Searching Memories by Keyword")
  println("-" * 40)

  val searchExamples = filterExamples.flatMap { m =>
    for {
      // Search for Scala-related content
      scalaResults <- m.store.search("Scala JVM", topK = 5)
      _ = println(s"\nSearch results for 'Scala JVM':")
      _ = scalaResults.foreach(sr => println(f"  Score: ${sr.score}%.2f - ${sr.memory.content.take(50)}..."))

      // Search for FP content
      fpResults <- m.store.search("functional programming", topK = 5)
      _ = println(s"\nSearch results for 'functional programming':")
      _ = fpResults.foreach(sr => println(f"  Score: ${sr.score}%.2f - ${sr.memory.content.take(50)}..."))
    } yield m
  }

  // === Part 5: Getting Context ===
  println("\n5. Getting Formatted Context")
  println("-" * 40)

  val contextExamples = searchExamples.flatMap { m =>
    for {
      // Get entity context
      entityContext <- m.getEntityContext(entityId)
      _ = println("\nEntity context for 'Scala':")
      _ = if (entityContext.nonEmpty) println(entityContext) else println("  (none)")

      // Get user context
      userContext <- m.getUserContext(Some("user-123"))
      _ = println("\nUser context:")
      _ = if (userContext.nonEmpty) println(userContext) else println("  (none)")

      // Get relevant context for a query
      relevantContext <- m.getRelevantContext("Tell me about Scala and FP", maxTokens = 500)
      _ = println("\nRelevant context for 'Tell me about Scala and FP':")
      _ = if (relevantContext.nonEmpty) println(relevantContext) else println("  (none)")
    } yield ()
  }

  // === Part 6: Statistics ===
  println("\n6. Memory Statistics")
  println("-" * 40)

  filterExamples.foreach { m =>
    m.stats.foreach { stats =>
      println(s"""
        |Memory Statistics:
        |  Total memories: ${stats.totalMemories}
        |  By type:
        |${stats.byType.map { case (t, c) => s"    - ${t.name}: $c" }.mkString("\n")}
        |  Distinct entities: ${stats.entityCount}
        |  Embedded: ${stats.embeddedCount}
        |""".stripMargin)
    }
  }

  contextExamples match {
    case Right(_) =>
      println("\n" + "=" * 50)
      println("Basic memory example completed successfully!")
    case Left(error) =>
      println(s"\nExample failed with error: ${error.message}")
  }
}
