package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.rag.loader.{ DirectoryLoader, DocumentLoader, LoadingConfig }
import org.llm4s.trace.EnhancedTracing
import org.llm4s.vectorstore.FusionStrategy

/**
 * Configuration for RAG pipeline.
 *
 * Uses immutable copy pattern for fluent configuration.
 * All settings have sensible defaults for quick start.
 *
 * @example
 * {{{
 * // Minimal configuration
 * val config = RAGConfig()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *
 * // Full customization
 * val config = RAGConfig()
 *   .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")
 *   .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)
 *   .withRRF(60)
 *   .withSQLite("./rag.db")
 *   .withLLM(llmClient)
 * }}}
 */
final case class RAGConfig(
  // Embedding settings
  embeddingProvider: EmbeddingProvider = EmbeddingProvider.OpenAI,
  embeddingModel: Option[String] = None,
  embeddingDimensions: Option[Int] = None,
  // Chunking settings
  chunkingStrategy: ChunkerFactory.Strategy = ChunkerFactory.Strategy.Sentence,
  chunkingConfig: ChunkingConfig = ChunkingConfig.default,
  // Search settings
  fusionStrategy: FusionStrategy = FusionStrategy.RRF(),
  topK: Int = 5,
  // Reranking settings
  rerankingStrategy: RerankingStrategy = RerankingStrategy.None,
  rerankTopK: Int = 30,
  // Storage settings
  vectorStorePath: Option[String] = None,
  keywordIndexPath: Option[String] = None,
  // Answer generation
  llmClient: Option[LLMClient] = None,
  systemPrompt: Option[String] = None,
  // Observability
  tracer: Option[EnhancedTracing] = None,
  // Document loading
  documentLoaders: Seq[DocumentLoader] = Seq.empty,
  loadingConfig: LoadingConfig = LoadingConfig.default
) {

  // ========== Embedding Configuration ==========

  /** Configure embedding provider */
  def withEmbeddings(provider: EmbeddingProvider): RAGConfig =
    copy(embeddingProvider = provider)

  /** Configure embedding provider and model */
  def withEmbeddings(provider: EmbeddingProvider, model: String): RAGConfig =
    copy(embeddingProvider = provider, embeddingModel = Some(model))

  /** Configure embedding provider, model, and dimensions */
  def withEmbeddings(provider: EmbeddingProvider, model: String, dimensions: Int): RAGConfig =
    copy(embeddingProvider = provider, embeddingModel = Some(model), embeddingDimensions = Some(dimensions))

  /** Override embedding dimensions (auto-detected by default) */
  def withEmbeddingDimensions(dims: Int): RAGConfig =
    copy(embeddingDimensions = Some(dims))

  // ========== Chunking Configuration ==========

  /** Configure chunking strategy */
  def withChunking(strategy: ChunkerFactory.Strategy): RAGConfig =
    copy(chunkingStrategy = strategy)

  /** Configure chunking strategy with custom config */
  def withChunking(strategy: ChunkerFactory.Strategy, config: ChunkingConfig): RAGConfig =
    copy(chunkingStrategy = strategy, chunkingConfig = config)

  /** Configure chunking with size and overlap */
  def withChunking(strategy: ChunkerFactory.Strategy, size: Int, overlap: Int): RAGConfig =
    copy(
      chunkingStrategy = strategy,
      chunkingConfig = ChunkingConfig(targetSize = size, maxSize = (size * 1.5).toInt, overlap = overlap)
    )

  // ========== Search Configuration ==========

  /** Configure fusion strategy */
  def withFusion(strategy: FusionStrategy): RAGConfig =
    copy(fusionStrategy = strategy)

  /** Use RRF fusion with custom k parameter */
  def withRRF(k: Int = 60): RAGConfig =
    copy(fusionStrategy = FusionStrategy.RRF(k))

  /** Use weighted score fusion */
  def withWeightedScore(vectorWeight: Double = 0.5, keywordWeight: Double = 0.5): RAGConfig =
    copy(fusionStrategy = FusionStrategy.WeightedScore(vectorWeight, keywordWeight))

  /** Use vector search only (no keyword) */
  def vectorOnly: RAGConfig =
    copy(fusionStrategy = FusionStrategy.VectorOnly)

  /** Use keyword search only (no vector) */
  def keywordOnly: RAGConfig =
    copy(fusionStrategy = FusionStrategy.KeywordOnly)

  /** Configure number of results to return */
  def withTopK(k: Int): RAGConfig =
    copy(topK = k)

  // ========== Reranking Configuration ==========

  /** Configure reranking strategy */
  def withReranking(strategy: RerankingStrategy): RAGConfig =
    copy(rerankingStrategy = strategy)

  /** Use Cohere cross-encoder reranking */
  def withCohereReranking(model: String = "rerank-english-v3.0"): RAGConfig =
    copy(rerankingStrategy = RerankingStrategy.Cohere(model))

  /** Use LLM-based reranking */
  def withLLMReranking: RAGConfig =
    copy(rerankingStrategy = RerankingStrategy.LLM)

  /** Configure number of candidates to rerank */
  def withRerankTopK(k: Int): RAGConfig =
    copy(rerankTopK = k)

  // ========== Storage Configuration ==========

  /** Use SQLite for persistent storage */
  def withSQLite(dbPath: String): RAGConfig =
    copy(
      vectorStorePath = Some(dbPath),
      keywordIndexPath = Some(dbPath.replace(".db", "-fts.db"))
    )

  /** Use in-memory storage (default) */
  def inMemory: RAGConfig =
    copy(vectorStorePath = None, keywordIndexPath = None)

  // ========== Answer Generation Configuration ==========

  /** Configure LLM client for answer generation */
  def withLLM(client: LLMClient): RAGConfig =
    copy(llmClient = Some(client))

  /** Configure system prompt for answer generation */
  def withSystemPrompt(prompt: String): RAGConfig =
    copy(systemPrompt = Some(prompt))

  // ========== Observability Configuration ==========

  /** Enable tracing for cost tracking and observability */
  def withTracing(tracer: EnhancedTracing): RAGConfig =
    copy(tracer = Some(tracer))

  // ========== Document Loading Configuration ==========

  /**
   * Add a document loader for build-time ingestion.
   *
   * Documents will be ingested when RAG.build() is called.
   */
  def withDocuments(loader: DocumentLoader): RAGConfig =
    copy(documentLoaders = documentLoaders :+ loader)

  /**
   * Add documents from a directory path.
   */
  def withDocuments(path: String): RAGConfig =
    withDocuments(DirectoryLoader(path))

  /**
   * Add multiple document loaders.
   */
  def withDocuments(loaders: Seq[DocumentLoader]): RAGConfig =
    copy(documentLoaders = documentLoaders ++ loaders)

  /**
   * Configure document loading behavior.
   */
  def withLoadingConfig(config: LoadingConfig): RAGConfig =
    copy(loadingConfig = config)

  /**
   * Set whether to stop on first loading error.
   */
  def failOnLoadError(fail: Boolean): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(failFast = fail))

  /**
   * Set parallelism for document processing.
   */
  def withParallelism(n: Int): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(parallelism = n))

  /**
   * Set batch size for embedding operations.
   */
  def withBatchSize(n: Int): RAGConfig =
    copy(loadingConfig = loadingConfig.copy(batchSize = n))
}

object RAGConfig {

  /** Default configuration - OpenAI embeddings, sentence chunking, RRF fusion, in-memory */
  val default: RAGConfig = RAGConfig()

  /** Configuration for production use with persistent storage */
  def production(dbPath: String): RAGConfig =
    RAGConfig()
      .withSQLite(dbPath)
      .withChunking(ChunkerFactory.Strategy.Sentence, 800, 150)

  /** Configuration for development/testing with smaller top-k */
  val development: RAGConfig =
    RAGConfig().inMemory
      .withTopK(3)
}
