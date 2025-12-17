package org.llm4s.samples.rag

import org.llm4s.rag.loader._

/**
 * Example demonstrating the DocumentLoader API for RAG pipelines.
 *
 * Shows how to:
 * - Load documents from various sources (files, URLs, text)
 * - Use document loaders with RAG.builder()
 * - Perform incremental sync operations
 * - Combine multiple loaders
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.DocumentLoaderExample"
 * }}}
 */
object DocumentLoaderExample extends App {

  println("=== Document Loader Example ===\n")

  // ========== 1. Basic TextLoader Usage ==========
  println("1. Creating documents with TextLoader...")

  val textLoader = TextLoader
    .builder()
    .add("doc-1", "Scala is a modern programming language that combines object-oriented and functional programming.")
    .add("doc-2", "LLM4S is a framework for building LLM-powered applications in Scala.")
    .add("doc-3", "RAG (Retrieval-Augmented Generation) improves LLM responses by providing relevant context.")
    .build()

  println(s"   Created loader with ${textLoader.estimatedCount.getOrElse("?")} documents")

  // ========== 2. FileLoader and DirectoryLoader ==========
  println("\n2. FileLoader and DirectoryLoader...")

  // Single file loader
  val fileLoader = FileLoader("./README.md")
  println(s"   FileLoader: ${fileLoader.description}")

  // Directory loader with filtering
  val dirLoader = DirectoryLoader("./docs")
    .withExtensions(Set(".md", ".txt"))
    .withRecursive(true)
  println(s"   DirectoryLoader: ${dirLoader.description}")

  // ========== 3. Combining Loaders ==========
  println("\n3. Combining multiple loaders...")

  val combinedLoader = textLoader ++ fileLoader
  println(s"   Combined: ${combinedLoader.description}")

  // Using DocumentLoaders combinators
  val filteredLoader = DocumentLoaders.filter(textLoader)(doc => doc.content.contains("Scala"))
  println(s"   Filtered: {filteredLoader.description}")

  // ========== 4. Build-Time Loading ==========
  println("\n4. Build-time loading with RAG.builder()...")
  println("   (Requires embedding provider environment variables)")

  // Example of build-time loading:
  // val rag = RAG.builder()
  //   .withEmbeddings(EmbeddingProvider.OpenAI)
  //   .withDocuments(textLoader)
  //   .withDocuments("./docs")  // Can also use path strings
  //   .build()
  //   .toOption.get

  // ========== 5. Ingest-Time Loading ==========
  println("\n5. Ingest-time loading...")
  println("   Example: rag.ingest(DirectoryLoader(\"./new-docs\"))")

  // val stats = rag.ingest(DirectoryLoader("./new-docs"))
  // stats match {
  //   case Right(s) =>
  //     println(s"   Loaded ${s.successful} documents, ${s.failed} failed, ${s.skipped} skipped")
  //   case Left(error) =>
  //     println(s"   Error: ${error.message}")
  // }

  // ========== 6. Incremental Sync ==========
  println("\n6. Incremental sync operations...")
  println("   Example: rag.sync(DirectoryLoader(\"./docs\"))")

  // Sync only processes changed documents:
  // val syncStats = rag.sync(DirectoryLoader("./docs"))
  // syncStats match {
  //   case Right(s) =>
  //     println(s"   Added: ${s.added}, Updated: ${s.updated}, Deleted: ${s.deleted}, Unchanged: ${s.unchanged}")
  //   case Left(error) =>
  //     println(s"   Error: ${error.message}")
  // }

  // ========== 7. Document Hints ==========
  println("\n7. Using document hints for chunking optimization...")

  val codeDoc = Document(
    id = "code-sample",
    content = "```scala\ndef hello(): Unit = println(\"Hello\")\n```",
    hints = Some(DocumentHints.code)
  )
  println(s"   Code document with hints: ${codeDoc.hints.map(_.chunkingStrategy).flatten}")

  val markdownDoc = Document(
    id = "markdown-sample",
    content = "# Title\n\nSome markdown content",
    hints = Some(DocumentHints.markdown)
  )
  println(s"   Markdown document with hints: ${markdownDoc.hints.map(_.chunkingStrategy).flatten}")

  // ========== 8. LoadResult Handling ==========
  println("\n8. Handling LoadResults...")

  val results = textLoader.load().toList
  val summary = results.groupBy {
    case _: LoadResult.Success => "success"
    case _: LoadResult.Failure => "failure"
    case _: LoadResult.Skipped => "skipped"
  }

  println(s"   Results: ${summary.map { case (k, v) => s"$k=${v.size}" }.mkString(", ")}")

  // ========== 9. Version Tracking ==========
  println("\n9. Document version tracking...")

  val docWithVersion = Document.create("Some content with auto-generated version")
  println(s"   Document ID: ${docWithVersion.id}")
  println(s"   Content hash: ${docWithVersion.version.map(_.contentHash.take(16) + "...").getOrElse("none")}")

  // ========== 10. Loading Configuration ==========
  println("\n10. Loading configuration options...")

  val strictConfig   = LoadingConfig.strict          // Fail on first error
  val lenientConfig  = LoadingConfig.lenient         // Continue on errors, skip empty
  val highPerfConfig = LoadingConfig.highPerformance // More parallelism

  println(s"    Strict: failFast=${strictConfig.failFast}")
  println(s"    Lenient: failFast=${lenientConfig.failFast}, skipEmpty=${lenientConfig.skipEmptyDocuments}")
  println(s"    HighPerf: parallelism=${highPerfConfig.parallelism}, batchSize=${highPerfConfig.batchSize}")

  println("\n=== Example Complete ===")
}
