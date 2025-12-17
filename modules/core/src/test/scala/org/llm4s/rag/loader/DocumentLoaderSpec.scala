package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DocumentLoaderSpec extends AnyFlatSpec with Matchers {

  // ========== Document Tests ==========

  "Document" should "create with auto-generated ID" in {
    val doc = Document.create("test content").ensureVersion
    doc.id should not be empty
    doc.content shouldBe "test content"
    doc.version shouldBe defined
  }

  it should "compute content hash for versioning" in {
    val doc1 = Document.create("same content").ensureVersion
    val doc2 = Document.create("same content").ensureVersion
    val doc3 = Document.create("different content").ensureVersion

    doc1.version.map(_.contentHash) shouldBe doc2.version.map(_.contentHash)
    doc1.version.map(_.contentHash) should not be doc3.version.map(_.contentHash)
  }

  it should "preserve metadata when adding more" in {
    val doc     = Document("id", "content", Map("key1" -> "value1"))
    val updated = doc.withMetadata(Map("key2" -> "value2"))

    updated.metadata should contain("key1" -> "value1")
    updated.metadata should contain("key2" -> "value2")
  }

  // ========== DocumentVersion Tests ==========

  "DocumentVersion" should "generate consistent hashes for same content" in {
    val v1 = DocumentVersion.fromContent("test content")
    val v2 = DocumentVersion.fromContent("test content")

    v1.contentHash shouldBe v2.contentHash
  }

  it should "generate different hashes for different content" in {
    val v1 = DocumentVersion.fromContent("content A")
    val v2 = DocumentVersion.fromContent("content B")

    v1.contentHash should not be v2.contentHash
  }

  // ========== DocumentHints Tests ==========

  "DocumentHints" should "provide markdown preset" in {
    val hints = DocumentHints.markdown
    hints.chunkingStrategy shouldBe defined
  }

  it should "provide code preset" in {
    val hints = DocumentHints.code
    hints.chunkingStrategy shouldBe defined
    hints.chunkingConfig shouldBe defined
  }

  it should "provide skip hint with reason" in {
    val hints = DocumentHints.skip("too large")
    hints.skipReason shouldBe Some("too large")
  }

  // ========== TextLoader Tests ==========

  "TextLoader" should "load documents from content" in {
    val loader  = TextLoader("content", "doc-1")
    val results = loader.load().toList

    (results should have).length(1)
    results.head match {
      case LoadResult.Success(doc) =>
        doc.id shouldBe "doc-1"
        doc.content shouldBe "content"
      case _ => fail("Expected success")
    }
  }

  it should "create multiple documents from pairs" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "content1",
      "id2" -> "content2"
    )

    loader.estimatedCount shouldBe Some(2)
    (loader.load().toList should have).length(2)
  }

  it should "build documents fluently" in {
    val loader = TextLoader
      .builder()
      .add("doc1", "content1")
      .add("doc2", "content2", Map("key" -> "value"))
      .build()

    loader.estimatedCount shouldBe Some(2)
  }

  // ========== FileLoader Tests ==========

  "FileLoader" should "fail for non-existent files" in {
    val loader  = FileLoader("/nonexistent/path/file.txt")
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
  }

  // ========== DirectoryLoader Tests ==========

  "DirectoryLoader" should "fail for non-existent directories" in {
    val loader  = DirectoryLoader("/nonexistent/directory")
    val results = loader.load().toList

    (results should have).length(1)
    results.head shouldBe a[LoadResult.Failure]
  }

  it should "filter by extensions" in {
    val loader = DirectoryLoader("./")
      .withExtensions(Set(".scala", ".md"))

    // Just verify it doesn't throw
    loader.extensions should contain("scala")
    loader.extensions should contain("md")
  }

  // ========== DocumentLoaders Combinators Tests ==========

  "DocumentLoaders.combine" should "combine multiple loaders" in {
    val loader1 = TextLoader("content1", "id1")
    val loader2 = TextLoader("content2", "id2")

    val combined = DocumentLoaders.combine(Seq(loader1, loader2))
    val results  = combined.load().toList

    (results should have).length(2)
  }

  "DocumentLoaders.filter" should "filter documents by predicate" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "hello world",
      "id2" -> "goodbye world",
      "id3" -> "hello again"
    )

    val filtered = DocumentLoaders.filter(loader)(_.content.contains("hello"))
    val results  = filtered.load().collect { case LoadResult.Success(d) => d }.toList

    (results should have).length(2)
    (results.map(_.id) should contain).allOf("id1", "id3")
  }

  "DocumentLoaders.map" should "transform documents" in {
    val loader = TextLoader("original", "id1")

    val mapped  = DocumentLoaders.map(loader)(doc => doc.copy(content = doc.content.toUpperCase))
    val results = mapped.load().collect { case LoadResult.Success(d) => d }.toList

    results.head.content shouldBe "ORIGINAL"
  }

  "DocumentLoaders.take" should "limit number of documents" in {
    val loader = TextLoader.fromPairs(
      "id1" -> "c1",
      "id2" -> "c2",
      "id3" -> "c3"
    )

    val limited = DocumentLoaders.take(loader, 2)
    (limited.load().toList should have).length(2)
  }

  "DocumentLoader.++" should "combine loaders using operator" in {
    val loader1 = TextLoader("c1", "id1")
    val loader2 = TextLoader("c2", "id2")

    val combined = loader1 ++ loader2
    (combined.load().toList should have).length(2)
  }

  // ========== LoadResult Tests ==========

  "LoadResult" should "create success results" in {
    val doc    = Document("id", "content")
    val result = LoadResult.success(doc)

    result shouldBe a[LoadResult.Success]
    result.asInstanceOf[LoadResult.Success].document shouldBe doc
  }

  it should "create failure results" in {
    val error  = org.llm4s.error.ProcessingError("test", "error message")
    val result = LoadResult.failure("source", error)

    result shouldBe a[LoadResult.Failure]
    result.asInstanceOf[LoadResult.Failure].source shouldBe "source"
  }

  it should "create skipped results" in {
    val result = LoadResult.skipped("source", "reason")

    result shouldBe a[LoadResult.Skipped]
    result.asInstanceOf[LoadResult.Skipped].reason shouldBe "reason"
  }

  // ========== LoadStats Tests ==========

  "LoadStats" should "calculate derived statistics" in {
    val stats = LoadStats(10, 7, 2, 1, Seq.empty)

    stats.totalAttempted shouldBe 10
    stats.successRate should be(0.7 +- 0.01)
    stats.hasErrors shouldBe true
  }

  it should "report no errors when all successful" in {
    val stats = LoadStats(5, 5, 0, 0, Seq.empty)

    stats.hasErrors shouldBe false
    stats.successRate shouldBe 1.0
  }

  // ========== SyncStats Tests ==========

  "SyncStats" should "calculate total and changed counts" in {
    val stats = SyncStats(added = 5, updated = 2, deleted = 1, unchanged = 42)

    stats.total shouldBe 50
    stats.changed shouldBe 8
  }

  // ========== DocumentRegistry Tests ==========

  "InMemoryDocumentRegistry" should "register and retrieve versions" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("test")

    registry.register("doc-1", version) shouldBe Right(())
    registry.getVersion("doc-1") shouldBe Right(Some(version))
  }

  it should "return None for unknown documents" in {
    val registry = InMemoryDocumentRegistry()
    registry.getVersion("unknown") shouldBe Right(None)
  }

  it should "unregister documents" in {
    val registry = InMemoryDocumentRegistry()
    val version  = DocumentVersion.fromContent("test")

    registry.register("doc-1", version)
    registry.unregister("doc-1") shouldBe Right(())
    registry.getVersion("doc-1") shouldBe Right(None)
  }

  it should "list all document IDs" in {
    val registry = InMemoryDocumentRegistry()

    registry.register("doc-1", DocumentVersion.fromContent("c1"))
    registry.register("doc-2", DocumentVersion.fromContent("c2"))

    registry.allDocumentIds() shouldBe Right(Set("doc-1", "doc-2"))
  }

  it should "clear all registrations" in {
    val registry = InMemoryDocumentRegistry()

    registry.register("doc-1", DocumentVersion.fromContent("c1"))
    registry.clear() shouldBe Right(())
    registry.allDocumentIds() shouldBe Right(Set.empty)
  }

  // ========== LoadingConfig Tests ==========

  "LoadingConfig" should "have sensible defaults" in {
    val config = LoadingConfig.default

    config.failFast shouldBe false
    config.useHints shouldBe true
    config.skipEmptyDocuments shouldBe true
    config.parallelism should be > 0
  }

  it should "provide strict preset" in {
    LoadingConfig.strict.failFast shouldBe true
  }

  it should "provide lenient preset" in {
    LoadingConfig.lenient.failFast shouldBe false
  }

  it should "provide high performance preset" in {
    val hp = LoadingConfig.highPerformance
    hp.parallelism should be > LoadingConfig.default.parallelism
  }

  it should "support fluent configuration" in {
    val config = LoadingConfig.default.withFailFast
      .withParallelism(8)
      .withBatchSize(20)

    config.failFast shouldBe true
    config.parallelism shouldBe 8
    config.batchSize shouldBe 20
  }
}
