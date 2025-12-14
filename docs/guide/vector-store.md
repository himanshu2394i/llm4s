---
layout: page
title: Vector Store
parent: User Guide
nav_order: 3
---

# Vector Store
{: .no_toc }

Low-level vector storage abstraction for RAG and semantic search.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The `VectorStore` trait provides a backend-agnostic interface for storing and searching vector embeddings. This is the foundation layer for building RAG (Retrieval-Augmented Generation) applications.

**Key Features:**
- Backend-agnostic API supporting multiple vector databases
- Type-safe error handling with `Result[A]`
- Metadata filtering with composable DSL
- Batch operations for efficient bulk processing
- Built-in statistics and monitoring

**Current Backends:**
- **SQLite** - File-based or in-memory storage (default)

**Planned Backends:**
- pgvector (PostgreSQL)
- Qdrant
- Milvus
- Pinecone

---

## Quick Start

### In-Memory Store

```scala
import org.llm4s.vectorstore._

// Create an in-memory store
val store = VectorStoreFactory.inMemory().fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Store a vector
val record = VectorRecord(
  id = "doc-1",
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Hello world"),
  metadata = Map("type" -> "greeting")
)

store.upsert(record)

// Search for similar vectors
val results = store.search(
  queryVector = Array(0.1f, 0.2f, 0.3f),
  topK = 5
)

results.foreach { scored =>
  println(s"${scored.record.id}: ${scored.score}")
}

// Clean up
store.close()
```

### File-Based Store

```scala
import org.llm4s.vectorstore._

// Create a persistent store
val store = VectorStoreFactory.sqlite("/path/to/vectors.db").fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Use the store...

store.close()
```

---

## Core Concepts

### VectorRecord

A `VectorRecord` represents a single entry in the vector store:

```scala
final case class VectorRecord(
  id: String,                          // Unique identifier
  embedding: Array[Float],             // Vector embedding
  content: Option[String] = None,      // Optional text content
  metadata: Map[String, String] = Map.empty  // Key-value metadata
)
```

**Creating Records:**

```scala
// With explicit ID
val record1 = VectorRecord(
  id = "doc-123",
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Document text"),
  metadata = Map("source" -> "wiki", "lang" -> "en")
)

// With auto-generated ID
val record2 = VectorRecord.create(
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Another document")
)

// Add metadata fluently
val record3 = VectorRecord("id", Array(1.0f))
  .withMetadata("key1", "value1")
  .withMetadata(Map("key2" -> "value2", "key3" -> "value3"))
```

### ScoredRecord

Search results include similarity scores:

```scala
final case class ScoredRecord(
  record: VectorRecord,
  score: Double  // 0.0 to 1.0, higher is more similar
)
```

---

## Operations

### CRUD Operations

```scala
// Single record operations
store.upsert(record)           // Insert or replace
store.get("doc-id")            // Retrieve by ID
store.delete("doc-id")         // Delete by ID

// Batch operations (more efficient)
store.upsertBatch(records)     // Insert/replace multiple
store.getBatch(ids)            // Retrieve multiple
store.deleteBatch(ids)         // Delete multiple

// Clear all records
store.clear()
```

### Search

```scala
// Basic search
val results = store.search(
  queryVector = embeddingVector,
  topK = 10
)

// Search with metadata filter
val filter = MetadataFilter.Equals("type", "document")
val filtered = store.search(
  queryVector = embeddingVector,
  topK = 10,
  filter = Some(filter)
)
```

### Listing and Pagination

```scala
// List all records
val all = store.list()

// Paginate results
val page1 = store.list(limit = 10, offset = 0)
val page2 = store.list(limit = 10, offset = 10)

// List with filter
val docs = store.list(filter = Some(MetadataFilter.Equals("type", "doc")))
```

### Statistics

```scala
val stats = store.stats()

stats.foreach { s =>
  println(s"Total records: ${s.totalRecords}")
  println(s"Dimensions: ${s.dimensions}")
  println(s"Size: ${s.formattedSize}")
}
```

---

## Metadata Filtering

The `MetadataFilter` DSL allows composing complex filters:

### Basic Filters

```scala
import org.llm4s.vectorstore.MetadataFilter._

// Exact match
val byType = Equals("type", "document")

// Contains substring
val byContent = Contains("summary", "Scala")

// Has key (any value)
val hasAuthor = HasKey("author")

// Value in set
val byLang = In("lang", Set("en", "es", "fr"))
```

### Combining Filters

```scala
// AND - both conditions must match
val andFilter = Equals("type", "doc").and(Equals("lang", "en"))

// OR - either condition can match
val orFilter = Equals("type", "doc").or(Equals("type", "article"))

// NOT - negate a filter
val notFilter = !Equals("archived", "true")

// Complex combinations
val complex = Equals("type", "doc")
  .and(Equals("lang", "en").or(Equals("lang", "es")))
  .and(!Equals("draft", "true"))
```

### Using Filters

```scala
// In search
store.search(queryVector, topK = 10, filter = Some(byType))

// In list
store.list(filter = Some(complex))

// In count
store.count(filter = Some(byType))

// Delete by filter
store.deleteByFilter(Equals("archived", "true"))
```

---

## Factory Configuration

### Using VectorStoreFactory

```scala
import org.llm4s.vectorstore._

// In-memory (default)
val memStore = VectorStoreFactory.inMemory()

// File-based SQLite
val fileStore = VectorStoreFactory.sqlite("/path/to/db.sqlite")

// From provider name
val store = VectorStoreFactory.create("sqlite", path = Some("/path/to/db.sqlite"))

// From config object
val config = VectorStoreFactory.Config.sqlite("/path/to/db.sqlite")
val configStore = VectorStoreFactory.create(config)
```

### Configuration Options

```scala
// Default in-memory config
val defaultConfig = VectorStoreFactory.Config.default

// SQLite file config
val sqliteConfig = VectorStoreFactory.Config.sqlite("/path/to/vectors.db")

// In-memory config
val memConfig = VectorStoreFactory.Config.inMemory

// With options
val withOptions = VectorStoreFactory.Config()
  .withSQLite("/path/to/db.sqlite")
  .withOption("cache_size", "10000")
```

---

## Integration with RAG Pipeline

### Complete RAG Example

```scala
import org.llm4s.vectorstore._
import org.llm4s.llmconnect.{LLMConnect, EmbeddingClient}

// 1. Create embedding client and vector store
val embeddingClient = EmbeddingClient.fromEnv().getOrElse(???)
val vectorStore = VectorStoreFactory.inMemory().getOrElse(???)

// 2. Ingest documents
val documents = Seq(
  "Scala is a programming language",
  "LLM4S provides LLM integration",
  "Vector stores enable semantic search"
)

documents.zipWithIndex.foreach { case (doc, idx) =>
  val embedding = embeddingClient.embed(doc).getOrElse(???)
  vectorStore.upsert(VectorRecord(
    id = s"doc-$idx",
    embedding = embedding,
    content = Some(doc)
  ))
}

// 3. Query with retrieval
val query = "What is Scala?"
val queryEmbedding = embeddingClient.embed(query).getOrElse(???)

val relevant = vectorStore.search(queryEmbedding, topK = 3).getOrElse(Seq.empty)

// 4. Augment prompt with context
val context = relevant.map(_.record.content.getOrElse("")).mkString("\n")
val prompt = s"""Based on the following context:
$context

Answer this question: $query"""

// 5. Generate response
val llm = LLMConnect.fromEnv().getOrElse(???)
val response = llm.complete(prompt)
```

---

## Best Practices

### Resource Management

Always close stores when done:

```scala
val store = VectorStoreFactory.inMemory().getOrElse(???)
// Use scala.util.Using for automatic cleanup
scala.util.Using.resource(new java.io.Closeable {
  def close(): Unit = store.close()
}) { _ =>
  // Use the store
}
```

### Batch Operations

Use batch operations for efficiency:

```scala
// Good - single batch call
store.upsertBatch(records)

// Less efficient - individual calls
records.foreach(store.upsert)
```

### Error Handling

All operations return `Result[A]`:

```scala
store.search(query, topK = 10) match {
  case Right(results) =>
    results.foreach(r => println(r.score))
  case Left(error) =>
    println(s"Search failed: ${error.formatted}")
}

// Or use for-comprehension
for {
  results <- store.search(query, topK = 10)
  count <- store.count()
} yield (results, count)
```

### Metadata Design

Design metadata for your filtering needs:

```scala
// Good - filterable metadata
VectorRecord(
  id = "doc-1",
  embedding = embedding,
  metadata = Map(
    "type" -> "article",
    "source" -> "wikipedia",
    "lang" -> "en",
    "year" -> "2024"
  )
)

// Then filter efficiently
store.search(
  query,
  topK = 10,
  filter = Some(Equals("type", "article").and(Equals("lang", "en")))
)
```

---

## Performance Considerations

### SQLite Backend

The SQLite backend is suitable for:
- Development and testing
- Small to medium datasets (~100K vectors)
- Single-machine deployments
- Scenarios where simplicity is preferred

**Limitations:**
- Vector similarity computed in Scala (not hardware-accelerated)
- All candidate vectors loaded into memory during search
- No built-in sharding or replication

For larger datasets or production workloads with high QPS requirements, consider pgvector or Qdrant (coming soon).

---

## API Reference

### VectorStore Trait

```scala
trait VectorStore {
  def upsert(record: VectorRecord): Result[Unit]
  def upsertBatch(records: Seq[VectorRecord]): Result[Unit]
  def search(queryVector: Array[Float], topK: Int = 10,
             filter: Option[MetadataFilter] = None): Result[Seq[ScoredRecord]]
  def get(id: String): Result[Option[VectorRecord]]
  def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]]
  def delete(id: String): Result[Unit]
  def deleteBatch(ids: Seq[String]): Result[Unit]
  def deleteByFilter(filter: MetadataFilter): Result[Long]
  def count(filter: Option[MetadataFilter] = None): Result[Long]
  def list(limit: Int = 100, offset: Int = 0,
           filter: Option[MetadataFilter] = None): Result[Seq[VectorRecord]]
  def clear(): Result[Unit]
  def stats(): Result[VectorStoreStats]
  def close(): Unit
}
```

---

## Next Steps

- **[Embeddings Configuration](../getting-started/configuration#embeddings-configuration)** - Configure embedding providers
- **[Examples Gallery](/examples/#embeddings-examples)** - See RAG examples in action
- **[RAG in a Box Roadmap](../roadmap/)** - Upcoming vector store backends
