package org.llm4s.samples.memory

import org.llm4s.agent.memory._

import java.nio.file.{ Files, Path }

/**
 * Example demonstrating persistent memory storage with SQLite.
 *
 * This example shows how to:
 * - Create a file-based SQLite memory store
 * - Persist memories across sessions
 * - Use full-text search
 * - Handle database lifecycle
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.memory.SQLiteMemoryExample"
 */
object SQLiteMemoryExample extends App {

  println("=" * 60)
  println("SQLite Memory Store Example")
  println("=" * 60)

  // Create a temporary database file
  val dbPath: Path = Files.createTempFile("llm4s-memory-", ".db")
  println(s"\nDatabase location: $dbPath")

  // ============================================================
  // Part 1: Create store and populate with memories
  // ============================================================
  println("\n--- Part 1: Creating and Populating Store ---")

  val createResult = for {
    store <- SQLiteMemoryStore(dbPath.toString)

    // Store various types of memories
    _ = println("\nStoring knowledge memories...")
    _ <- store.store(
      Memory.fromKnowledge(
        "Scala is a multi-paradigm programming language that combines object-oriented and functional programming.",
        source = "scala-docs",
        chunkIndex = Some(1)
      )
    )
    _ <- store.store(
      Memory.fromKnowledge(
        "Scala runs on the Java Virtual Machine (JVM) and is fully interoperable with Java.",
        source = "scala-docs",
        chunkIndex = Some(2)
      )
    )
    _ <- store.store(
      Memory.fromKnowledge(
        "Functional programming emphasizes immutability and pure functions without side effects.",
        source = "fp-guide",
        chunkIndex = Some(1)
      )
    )

    _       = println("Storing entity memories...")
    scalaId = EntityId.fromName("Scala Language")
    _ <- store.store(Memory.forEntity(scalaId, "Scala Language", "Created by Martin Odersky", "programming_language"))
    _ <- store.store(Memory.forEntity(scalaId, "Scala Language", "First released in 2004", "programming_language"))

    _ = println("Storing conversation memories...")
    _ <- store.store(Memory.fromConversation("What is Scala?", "user", Some("conv-1")))
    _ <- store.store(Memory.fromConversation("Scala is a programming language...", "assistant", Some("conv-1")))

    count <- store.count()
    _ = println(s"\nTotal memories stored: $count")

  } yield store

  createResult match {
    case Left(error) =>
      println(s"Error creating store: $error")
      System.exit(1)

    case Right(store) =>
      // ============================================================
      // Part 2: Query the store
      // ============================================================
      println("\n--- Part 2: Querying Memories ---")

      // Full-text search
      println("\nSearching for 'functional programming'...")
      store.search("functional programming", topK = 3) match {
        case Right(results) =>
          results.foreach(scored => println(f"  Score: ${scored.score}%.3f - ${scored.memory.content.take(60)}..."))
        case Left(error) =>
          println(s"  Search error: $error")
      }

      // Filter by type
      println("\nKnowledge memories:")
      store.recall(MemoryFilter.ByType(MemoryType.Knowledge)) match {
        case Right(memories) =>
          memories.foreach(m => println(s"  - ${m.content.take(60)}..."))
        case Left(error) =>
          println(s"  Error: $error")
      }

      // Filter by entity
      println("\nScala entity facts:")
      store.recall(MemoryFilter.ByEntity(EntityId.fromName("Scala Language"))) match {
        case Right(memories) =>
          memories.foreach(m => println(s"  - ${m.content}"))
        case Left(error) =>
          println(s"  Error: $error")
      }

      // ============================================================
      // Part 3: Persistence demonstration
      // ============================================================
      println("\n--- Part 3: Demonstrating Persistence ---")
      println("Closing database connection...")
      store.close()

      println("Reopening database...")
      SQLiteMemoryStore(dbPath.toString) match {
        case Right(reopenedStore) =>
          reopenedStore.count() match {
            case Right(count) =>
              println(s"Memories recovered from disk: $count")
            case Left(error) =>
              println(s"Count error: $error")
          }

          println("\nSearching recovered memories for 'JVM'...")
          reopenedStore.search("JVM", topK = 2) match {
            case Right(results) =>
              results.foreach { scored =>
                println(f"  Score: ${scored.score}%.3f - ${scored.memory.content.take(60)}...")
              }
            case Left(error) =>
              println(s"  Search error: $error")
          }

          reopenedStore.close()

        case Left(error) =>
          println(s"Error reopening store: $error")
      }

      // ============================================================
      // Part 4: Using with SimpleMemoryManager
      // ============================================================
      println("\n--- Part 4: Using with SimpleMemoryManager ---")

      SQLiteMemoryStore(dbPath.toString) match {
        case Right(sqliteStore) =>
          // Clear for fresh start
          sqliteStore.clear()

          val config  = MemoryManagerConfig(defaultImportance = 0.5)
          val manager = SimpleMemoryManager.withStore(sqliteStore, config)

          val managerResult = for {
            m1 <- manager.recordKnowledge(
              "llm4s is a Scala library for building LLM applications",
              "readme",
              Map("version" -> "0.1")
            )
            m2 <- m1.recordKnowledge(
              "llm4s supports OpenAI, Anthropic, and Ollama providers",
              "readme",
              Map("section" -> "providers")
            )
            m3      <- m2.recordUserFact("Prefers functional programming style", Some("user-1"), None)
            context <- m3.getRelevantContext("Tell me about llm4s")
          } yield context

          managerResult match {
            case Right(context) =>
              println("Relevant context for 'Tell me about llm4s':")
              println(s"  $context")
            case Left(error) =>
              println(s"Manager error: $error")
          }

          sqliteStore.close()

        case Left(error) =>
          println(s"Error: $error")
      }

  }

  // Cleanup
  println("\n--- Cleanup ---")
  Files.deleteIfExists(dbPath)
  println(s"Deleted temporary database: $dbPath")

  println("\n" + "=" * 60)
  println("SQLite Memory Example Complete!")
  println("=" * 60)
}
