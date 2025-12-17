package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, DocumentChunk, DocumentChunker }
import org.llm4s.config.ConfigReader
import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.model._
import org.llm4s.rag.loader._
import org.llm4s.reranker.{ Reranker, RerankerFactory }
import org.llm4s.trace.EnhancedTracing
import org.llm4s.types.Result
import org.llm4s.vectorstore._

import java.io.{ Closeable, File }
import java.nio.file.Path

/**
 * High-level RAG (Retrieval-Augmented Generation) pipeline.
 *
 * Provides a unified interface for:
 * - Document ingestion (from files, directories, or raw text)
 * - Semantic search with hybrid fusion
 * - Answer generation with retrieved context
 *
 * @example
 * {{{
 * // Create pipeline
 * val rag = RAG.builder()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *   .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
 *   .build()
 *   .toOption.get
 *
 * // Ingest documents
 * rag.ingest("./docs")
 *
 * // Search
 * val results = rag.query("What is X?")
 *
 * // With answer generation (requires LLM client)
 * val ragWithLLM = RAG.builder()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *   .withLLM(llmClient)
 *   .build()
 *   .toOption.get
 *
 * val answer = ragWithLLM.queryWithAnswer("What is X?")
 * }}}
 */
final class RAG private (
  val config: RAGConfig,
  private val embeddingClient: EmbeddingClient,
  private val embeddingModelConfig: EmbeddingModelConfig,
  private val chunker: DocumentChunker,
  private val hybridSearcher: HybridSearcher,
  private val reranker: Option[Reranker],
  private val llmClient: Option[LLMClient],
  private val tracer: Option[EnhancedTracing],
  private val registry: DocumentRegistry
) extends Closeable {

  // Statistics tracking
  @volatile private var _documentCount: Int = 0
  @volatile private var _chunkCount: Int    = 0

  // Traced embedding client
  private lazy val tracedEmbeddingClient: EmbeddingClient = tracer match {
    case Some(t) => embeddingClient.withTracing(t).withOperation("rag")
    case None    => embeddingClient
  }

  // ========== Ingestion API ==========

  /**
   * Ingest a document from a file path.
   *
   * Supports: .txt, .md, .pdf, .docx and other text-like formats.
   *
   * @param path Path to file or directory
   * @param metadata Additional metadata to attach to all chunks
   * @return Number of chunks created
   */
  def ingest(path: String, metadata: Map[String, String] = Map.empty): Result[Int] =
    ingestPath(new File(path).toPath, metadata)

  /**
   * Ingest a document from a Path.
   */
  def ingest(path: Path): Result[Int] =
    ingestPath(path, Map.empty)

  /**
   * Ingest a document from a Path with metadata.
   */
  def ingestPath(path: Path, metadata: Map[String, String]): Result[Int] = {
    val file = path.toFile
    if (file.isDirectory) {
      ingestDirectory(file, metadata)
    } else {
      ingestFile(file, metadata)
    }
  }

  /**
   * Ingest raw text content.
   *
   * @param content The text content to ingest
   * @param documentId Unique identifier for this document
   * @param metadata Additional metadata
   * @return Number of chunks created
   */
  def ingestText(
    content: String,
    documentId: String,
    metadata: Map[String, String] = Map.empty
  ): Result[Int] = {
    val chunks = chunker.chunk(content, config.chunkingConfig)
    indexChunks(documentId, chunks, metadata)
  }

  /**
   * Ingest pre-chunked content (for advanced use cases).
   */
  def ingestChunks(
    documentId: String,
    chunks: Seq[String],
    metadata: Map[String, String] = Map.empty
  ): Result[Int] = {
    val docChunks = chunks.zipWithIndex.map { case (content, idx) =>
      DocumentChunk(content, idx)
    }
    indexChunks(documentId, docChunks, metadata)
  }

  // ========== Document Loader API ==========

  /**
   * Ingest documents from a DocumentLoader.
   *
   * @param loader The document loader to ingest from
   * @return Loading statistics with success/failure counts
   */
  def ingest(loader: DocumentLoader): Result[LoadStats] = {
    case class State(
      totalAttempted: Int = 0,
      successful: Int = 0,
      failed: Int = 0,
      skipped: Int = 0,
      errors: Seq[(String, org.llm4s.error.LLMError)] = Seq.empty,
      failFastError: Option[org.llm4s.error.LLMError] = None
    )

    val finalState = loader.load().foldLeft(State()) { (state, result) =>
      if (state.failFastError.isDefined) {
        state // Already failed, skip remaining
      } else {
        val newTotal = state.totalAttempted + 1
        result match {
          case LoadResult.Success(doc) =>
            if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
              state.copy(totalAttempted = newTotal, skipped = state.skipped + 1)
            } else {
              ingestDocument(doc) match {
                case Right(_) =>
                  state.copy(totalAttempted = newTotal, successful = state.successful + 1)
                case Left(error) =>
                  val newState = state.copy(
                    totalAttempted = newTotal,
                    failed = state.failed + 1,
                    errors = state.errors :+ (doc.id, error)
                  )
                  if (config.loadingConfig.failFast) {
                    newState.copy(failFastError = Some(error))
                  } else {
                    newState
                  }
              }
            }

          case LoadResult.Failure(source, error, _) =>
            val newState = state.copy(
              totalAttempted = newTotal,
              failed = state.failed + 1,
              errors = state.errors :+ (source, error)
            )
            if (config.loadingConfig.failFast) {
              newState.copy(failFastError = Some(error))
            } else {
              newState
            }

          case LoadResult.Skipped(_, _) =>
            state.copy(totalAttempted = newTotal, skipped = state.skipped + 1)
        }
      }
    }

    finalState.failFastError match {
      case Some(error) => Left(error)
      case None =>
        Right(
          LoadStats(
            finalState.totalAttempted,
            finalState.successful,
            finalState.failed,
            finalState.skipped,
            finalState.errors
          )
        )
    }
  }

  /**
   * Sync with a loader - only process changed documents.
   *
   * Compares document versions to detect:
   * - New documents (added)
   * - Changed documents (updated - old chunks removed, new chunks added)
   * - Deleted documents (removed from source)
   * - Unchanged documents (skipped)
   *
   * @param loader The document loader to sync with
   * @return Sync statistics
   */
  def sync(loader: DocumentLoader): Result[SyncStats] = {
    var added     = 0
    var updated   = 0
    var deleted   = 0
    var unchanged = 0
    val seenIds   = scala.collection.mutable.Set[String]()

    // Process all documents from loader
    loader.load().foreach {
      case LoadResult.Success(doc) =>
        if (config.loadingConfig.skipEmptyDocuments && doc.content.trim.isEmpty) {
          // Skip empty
        } else {
          seenIds += doc.id
          val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))

          registry.getVersion(doc.id) match {
            case Right(None) =>
              // New document
              ingestDocument(doc) match {
                case Right(_) =>
                  registry.register(doc.id, docVersion)
                  added += 1
                case Left(_) =>
                // Failed to ingest - skip
              }

            case Right(Some(existingVersion)) if existingVersion.contentHash != docVersion.contentHash =>
              // Changed document - delete old chunks, re-ingest
              deleteDocumentChunks(doc.id)
              ingestDocument(doc) match {
                case Right(_) =>
                  registry.register(doc.id, docVersion)
                  updated += 1
                case Left(_) =>
                // Failed to ingest - skip
              }

            case Right(Some(_)) =>
              // Unchanged
              unchanged += 1

            case Left(_) =>
            // Registry error - skip
          }
        }

      case LoadResult.Failure(_, _, _) | LoadResult.Skipped(_, _) =>
      // Skip failed/skipped documents
    }

    // Handle deletions - documents in registry but not in loader
    registry.allDocumentIds() match {
      case Right(registeredIds) =>
        val deletedIds = registeredIds -- seenIds
        deletedIds.foreach { id =>
          deleteDocumentChunks(id)
          registry.unregister(id)
          deleted += 1
        }

      case Left(_) =>
      // Registry error - skip deletion check
    }

    Right(SyncStats(added, updated, deleted, unchanged))
  }

  /**
   * Full refresh - re-process all documents.
   *
   * Clears the registry and re-ingests all documents.
   * Use this when you want to ensure a clean slate.
   *
   * @param loader The document loader to refresh from
   * @return Sync statistics (all as "added")
   */
  def refresh(loader: DocumentLoader): Result[SyncStats] =
    for {
      _     <- registry.clear()
      _     <- clear()
      stats <- ingest(loader)
    } yield SyncStats(added = stats.successful, updated = 0, deleted = 0, unchanged = 0)

  /**
   * Delete a specific document and its chunks.
   *
   * @param docId Document ID to delete
   * @return Unit on success
   */
  def deleteDocument(docId: String): Result[Unit] =
    for {
      _ <- deleteDocumentChunks(docId)
      _ <- registry.unregister(docId)
    } yield ()

  /**
   * Check if a document needs updating based on version.
   *
   * @param doc Document to check
   * @return true if document is new or changed
   */
  def needsUpdate(doc: Document): Result[Boolean] = {
    val docVersion = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
    registry.getVersion(doc.id).map {
      case None                                                             => true
      case Some(existing) if existing.contentHash != docVersion.contentHash => true
      case _                                                                => false
    }
  }

  private def ingestDocument(doc: Document): Result[Int] = {
    // Choose chunker based on hints if configured
    val effectiveChunker = if (config.loadingConfig.useHints) {
      doc.hints.flatMap(_.chunkingStrategy) match {
        case Some(strategy) => ChunkerFactory.create(strategy)
        case None           => chunker
      }
    } else {
      chunker
    }

    val effectiveConfig = if (config.loadingConfig.useHints) {
      doc.hints.flatMap(_.chunkingConfig).getOrElse(config.chunkingConfig)
    } else {
      config.chunkingConfig
    }

    val chunks = effectiveChunker.chunk(doc.content, effectiveConfig)
    val result = indexChunks(doc.id, chunks, doc.metadata)

    // Register version if enabled
    if (config.loadingConfig.enableVersioning) {
      val version = doc.version.getOrElse(DocumentVersion.fromContent(doc.content))
      registry.register(doc.id, version)
    }

    result
  }

  private def deleteDocumentChunks(docId: String): Result[Unit] =
    // Delete from vector store - find all chunks with this docId
    // For now, use pattern-based deletion (chunks are named "docId-chunk-N")
    for {
      _ <- hybridSearcher.vectorStore.deleteByPrefix(docId)
      _ <- hybridSearcher.keywordIndex.deleteByPrefix(docId)
    } yield ()

  // ========== Query API ==========

  /**
   * Search for relevant chunks.
   *
   * @param query The search query
   * @param topK Override default topK (optional)
   * @return Ranked search results
   */
  def query(query: String, topK: Option[Int] = None): Result[Seq[RAGSearchResult]] = {
    val k = topK.getOrElse(config.topK)

    for {
      queryEmbedding <- embedQuery(query)
      results        <- searchWithStrategy(queryEmbedding, query, k)
    } yield results.map(toSearchResult)
  }

  /**
   * Search and generate an answer using LLM.
   *
   * Requires an LLM client to be configured.
   *
   * @param question The question to answer
   * @param topK Override default topK (optional)
   * @return Answer with supporting contexts
   */
  def queryWithAnswer(question: String, topK: Option[Int] = None): Result[RAGAnswerResult] =
    llmClient match {
      case None =>
        Left(
          ConfigurationError(
            "LLM client required for answer generation. Use .withLLM(client) when building RAG."
          )
        )
      case Some(client) =>
        for {
          searchResults <- query(question, topK)
          answer        <- generateAnswer(client, question, searchResults)
        } yield answer
    }

  // ========== Statistics API ==========

  /** Number of documents ingested */
  def documentCount: Int = _documentCount

  /** Number of chunks indexed */
  def chunkCount: Int = _chunkCount

  /** Get store statistics */
  def stats: Result[RAGStats] =
    for {
      vectorCount <- hybridSearcher.vectorStore.count()
    } yield RAGStats(
      documentCount = _documentCount,
      chunkCount = _chunkCount,
      vectorCount = vectorCount
    )

  // ========== Lifecycle API ==========

  /**
   * Clear all indexed data.
   */
  def clear(): Result[Unit] =
    for {
      _ <- hybridSearcher.vectorStore.clear()
      _ <- hybridSearcher.keywordIndex.clear()
    } yield {
      _documentCount = 0
      _chunkCount = 0
    }

  /**
   * Close resources.
   */
  override def close(): Unit =
    hybridSearcher.close()

  // ========== Private Implementation ==========

  private val supportedExtensions = Set(".txt", ".md", ".pdf", ".docx", ".json", ".xml", ".html")

  private def ingestDirectory(dir: File, metadata: Map[String, String]): Result[Int] = {
    val files = Option(dir.listFiles())
      .map(_.toSeq)
      .getOrElse(Seq.empty)
      .filter(f => f.isFile && supportedExtensions.exists(ext => f.getName.toLowerCase.endsWith(ext)))

    files.foldLeft[Result[Int]](Right(0)) { (acc, file) =>
      for {
        count <- acc
        added <- ingestFile(file, metadata)
      } yield count + added
    }
  }

  private def ingestFile(file: File, metadata: Map[String, String]): Result[Int] = {
    val docId = file.getName

    UniversalExtractor.extract(file.getAbsolutePath) match {
      case Left(error) =>
        Left(ProcessingError("ingest", s"Failed to extract ${file.getName}: ${error.message}"))
      case Right(text) =>
        val fullMetadata = metadata + ("source" -> file.getName) + ("path" -> file.getAbsolutePath)
        val chunks       = chunker.chunkWithSource(text, file.getName, config.chunkingConfig)
        indexChunks(docId, chunks, fullMetadata)
    }
  }

  private def indexChunks(
    docId: String,
    chunks: Seq[DocumentChunk],
    metadata: Map[String, String]
  ): Result[Int] = {
    if (chunks.isEmpty) return Right(0)

    val contents = chunks.map(_.content)

    for {
      embeddings <- embedBatch(contents)
      _ <- {
        val vectorRecords = chunks.zip(embeddings).map { case (chunk, embedding) =>
          VectorRecord(
            id = s"$docId-chunk-${chunk.index}",
            embedding = embedding,
            content = Some(chunk.content),
            metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
          )
        }
        hybridSearcher.vectorStore.upsertBatch(vectorRecords)
      }
      _ <- {
        val keywordDocs = chunks.map { chunk =>
          KeywordDocument(
            id = s"$docId-chunk-${chunk.index}",
            content = chunk.content,
            metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
          )
        }
        hybridSearcher.keywordIndex.indexBatch(keywordDocs)
      }
    } yield {
      _chunkCount += chunks.size
      _documentCount += 1
      chunks.size
    }
  }

  private def embedQuery(query: String): Result[Array[Float]] = {
    val request = EmbeddingRequest(Seq(query), embeddingModelConfig)
    tracedEmbeddingClient.embed(request).map(_.embeddings.head.map(_.toFloat).toArray)
  }

  private def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = {
    val request = EmbeddingRequest(texts, embeddingModelConfig)
    tracedEmbeddingClient.embed(request).map(_.embeddings.map(_.map(_.toFloat).toArray))
  }

  private def searchWithStrategy(
    queryEmbedding: Array[Float],
    queryText: String,
    topK: Int
  ): Result[Seq[HybridSearchResult]] =
    reranker match {
      case Some(r) =>
        hybridSearcher.searchWithReranking(
          queryEmbedding,
          queryText,
          topK,
          config.rerankTopK,
          config.fusionStrategy,
          None,
          Some(r)
        )
      case None =>
        hybridSearcher.search(queryEmbedding, queryText, topK, config.fusionStrategy)
    }

  private def toSearchResult(hsr: HybridSearchResult): RAGSearchResult =
    RAGSearchResult(
      id = hsr.id,
      content = hsr.content,
      score = hsr.score,
      metadata = hsr.metadata,
      vectorScore = hsr.vectorScore,
      keywordScore = hsr.keywordScore
    )

  private def generateAnswer(
    client: LLMClient,
    question: String,
    contexts: Seq[RAGSearchResult]
  ): Result[RAGAnswerResult] = {
    val contextText = contexts.zipWithIndex
      .map { case (ctx, i) => s"[${i + 1}] ${ctx.content}" }
      .mkString("\n\n")

    val systemPrompt = config.systemPrompt.getOrElse(DefaultSystemPrompt)

    val userPrompt = s"""Context:
$contextText

Question: $question

Answer:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    client.complete(conversation, CompletionOptions(temperature = 0.0)).map { completion =>
      RAGAnswerResult(
        answer = completion.content,
        question = question,
        contexts = contexts,
        usage = completion.usage
      )
    }
  }

  private val DefaultSystemPrompt =
    """You are a helpful assistant that answers questions based on the provided context.
      |Use only the information from the context to answer the question.
      |If the context doesn't contain enough information, say so.
      |Be concise and accurate.""".stripMargin
}

object RAG {

  /**
   * Create a RAG builder with default configuration.
   */
  def builder(): RAGConfig = RAGConfig.default

  /**
   * Create a RAG pipeline from environment configuration.
   *
   * Reads embedding provider from EMBEDDING_PROVIDER and related env vars.
   * Creates an in-memory pipeline by default.
   */
  def fromEnv(): Result[RAG] =
    for {
      embeddingResult <- ConfigReader.Embeddings()
      (providerName, providerConfig) = embeddingResult
      embeddingClient <- EmbeddingClient.from(providerName, providerConfig)
      dims       = defaultDimensions.getOrElse(providerConfig.model, 1536)
      provider   = EmbeddingProvider.fromString(providerName).getOrElse(EmbeddingProvider.OpenAI)
      baseConfig = RAGConfig().withEmbeddings(provider).withEmbeddingDimensions(dims)
      rag <- build(baseConfig, Some(embeddingClient))
    } yield rag

  /**
   * Create a RAG pipeline from environment with LLM client.
   */
  def fromEnvWithLLM(): Result[RAG] =
    for {
      embeddingResult <- ConfigReader.Embeddings()
      (providerName, providerConfig) = embeddingResult
      embeddingClient <- EmbeddingClient.from(providerName, providerConfig)
      llmClient       <- LLMConnect.fromEnv()
      dims       = defaultDimensions.getOrElse(providerConfig.model, 1536)
      provider   = EmbeddingProvider.fromString(providerName).getOrElse(EmbeddingProvider.OpenAI)
      baseConfig = RAGConfig().withEmbeddings(provider).withEmbeddingDimensions(dims).withLLM(llmClient)
      rag <- build(baseConfig, Some(embeddingClient))
    } yield rag

  /**
   * Build a RAG pipeline from configuration.
   */
  def build(config: RAGConfig): Result[RAG] = build(config, None)

  private def build(config: RAGConfig, existingClient: Option[EmbeddingClient]): Result[RAG] =
    for {
      embeddingClient <- existingClient match {
        case Some(c) => Right(c)
        case None    => createEmbeddingClient(config)
      }
      embeddingModelConfig = createEmbeddingModelConfig(config)
      chunker              = createChunker(config, embeddingClient, embeddingModelConfig)
      hybridSearcher <- createHybridSearcher(config)
      reranker       <- createReranker(config)
      registry       <- createRegistry(config)
      rag = new RAG(
        config = config,
        embeddingClient = embeddingClient,
        embeddingModelConfig = embeddingModelConfig,
        chunker = chunker,
        hybridSearcher = hybridSearcher,
        reranker = reranker,
        llmClient = config.llmClient,
        tracer = config.tracer,
        registry = registry
      )
      // Process any pre-configured document loaders
      _ <-
        if (config.documentLoaders.nonEmpty) {
          config.documentLoaders.foldLeft[Result[Unit]](Right(())) { (acc, loader) =>
            acc.flatMap(_ => rag.ingest(loader).map(_ => ()))
          }
        } else {
          Right(())
        }
    } yield rag

  private def createRegistry(@scala.annotation.unused config: RAGConfig): Result[DocumentRegistry] =
    // For now, use in-memory registry
    // Future: SQLite registry when vectorStorePath is set
    Right(InMemoryDocumentRegistry())

  /**
   * Extension method to build from config.
   */
  implicit class RAGConfigOps(private val config: RAGConfig) extends AnyVal {
    def build(): Result[RAG] = RAG.build(config)
  }

  // ========== Private Builders ==========

  private def createEmbeddingClient(config: RAGConfig): Result[EmbeddingClient] = {
    val (baseUrl, providerName) = config.embeddingProvider match {
      case EmbeddingProvider.OpenAI => ("https://api.openai.com/v1", "openai")
      case EmbeddingProvider.Voyage => ("https://api.voyageai.com/v1", "voyage")
      case EmbeddingProvider.Ollama => ("http://localhost:11434", "ollama")
    }

    val model = config.embeddingModel.getOrElse(defaultModel(config.embeddingProvider))

    // Read API key from environment
    ConfigReader.Embeddings().flatMap { case (_, envConfig) =>
      val providerConfig = EmbeddingProviderConfig(
        baseUrl = baseUrl,
        model = model,
        apiKey = envConfig.apiKey
      )
      EmbeddingClient.from(providerName, providerConfig)
    }
  }

  private def createEmbeddingModelConfig(config: RAGConfig): EmbeddingModelConfig = {
    val model = config.embeddingModel.getOrElse(defaultModel(config.embeddingProvider))
    val dims  = config.embeddingDimensions.getOrElse(defaultDimensions.getOrElse(model, 1536))
    EmbeddingModelConfig(model, dims)
  }

  private def createChunker(
    config: RAGConfig,
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig
  ): DocumentChunker =
    config.chunkingStrategy match {
      case ChunkerFactory.Strategy.Semantic =>
        ChunkerFactory.semantic(embeddingClient, modelConfig)
      case other =>
        ChunkerFactory.create(other)
    }

  private def createHybridSearcher(config: RAGConfig): Result[HybridSearcher] =
    (config.vectorStorePath, config.keywordIndexPath) match {
      case (Some(vectorPath), Some(keywordPath)) =>
        HybridSearcher.sqlite(vectorPath, keywordPath)
      case (Some(vectorPath), None) =>
        HybridSearcher.sqlite(vectorPath, vectorPath.replace(".db", "-fts.db"))
      case _ =>
        HybridSearcher.inMemory()
    }

  private def createReranker(config: RAGConfig): Result[Option[Reranker]] =
    config.rerankingStrategy match {
      case RerankingStrategy.None =>
        Right(None)
      case RerankingStrategy.Cohere(_) =>
        ConfigReader.LLMConfig().flatMap { cfg =>
          RerankerFactory.fromEnv(cfg).map {
            case Some(r) => Some(r)
            case None    => None
          }
        }
      case RerankingStrategy.LLM =>
        config.llmClient match {
          case Some(client) =>
            Right(Some(RerankerFactory.llm(client)))
          case None =>
            Left(
              ConfigurationError(
                "LLM reranking requires an LLM client. Use .withLLM(client) when building RAG."
              )
            )
        }
    }

  private def defaultModel(provider: EmbeddingProvider): String = provider match {
    case EmbeddingProvider.OpenAI => "text-embedding-3-small"
    case EmbeddingProvider.Voyage => "voyage-3"
    case EmbeddingProvider.Ollama => "nomic-embed-text"
  }

  private val defaultDimensions: Map[String, Int] = Map(
    "text-embedding-3-small" -> 1536,
    "text-embedding-3-large" -> 3072,
    "text-embedding-ada-002" -> 1536,
    "voyage-3"               -> 1024,
    "voyage-3-lite"          -> 512,
    "voyage-code-3"          -> 1024,
    "nomic-embed-text"       -> 768,
    "mxbai-embed-large"      -> 1024,
    "all-minilm"             -> 384
  )
}
