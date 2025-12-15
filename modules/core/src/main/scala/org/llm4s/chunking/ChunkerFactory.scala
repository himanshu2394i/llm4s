package org.llm4s.chunking

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig

/**
 * Factory for creating document chunkers.
 *
 * Provides convenient factory methods for creating different chunking strategies.
 * Each strategy has different trade-offs between quality and performance.
 *
 * Usage:
 * {{{
 * // Simple character-based chunking (fastest)
 * val simple = ChunkerFactory.simple()
 *
 * // Sentence-aware chunking (recommended for most use cases)
 * val sentence = ChunkerFactory.sentence()
 *
 * // Markdown-aware chunking (preserves structure)
 * val markdown = ChunkerFactory.markdown()
 *
 * // Semantic chunking (highest quality, requires embeddings)
 * val modelConfig = EmbeddingModelConfig("text-embedding-3-small", 1536)
 * val semantic = ChunkerFactory.semantic(embeddingClient, modelConfig)
 *
 * // Auto-detect based on content
 * val auto = ChunkerFactory.auto(text)
 * }}}
 */
object ChunkerFactory {

  /** Chunking strategy type */
  sealed trait Strategy {
    def name: String
  }

  object Strategy {
    case object Simple   extends Strategy { val name = "simple"   }
    case object Sentence extends Strategy { val name = "sentence" }
    case object Semantic extends Strategy { val name = "semantic" }
    case object Markdown extends Strategy { val name = "markdown" }

    def fromString(s: String): Option[Strategy] = s.toLowerCase.trim match {
      case "simple"   => Some(Simple)
      case "sentence" => Some(Sentence)
      case "semantic" => Some(Semantic)
      case "markdown" => Some(Markdown)
      case _          => None
    }

    val all: Seq[Strategy] = Seq(Simple, Sentence, Semantic, Markdown)
  }

  /**
   * Create a simple character-based chunker.
   *
   * Fast but doesn't respect semantic boundaries.
   * Use for content without clear sentence structure.
   */
  def simple(): DocumentChunker = SimpleChunker()

  /**
   * Create a sentence-aware chunker.
   *
   * Respects sentence boundaries for better quality chunks.
   * Recommended for most text content.
   */
  def sentence(): DocumentChunker = SentenceChunker()

  /**
   * Create a markdown-aware chunker.
   *
   * Preserves markdown structure including:
   * - Heading boundaries and hierarchy
   * - Code blocks (keeps them intact)
   * - List structure
   *
   * Best for markdown documentation and README files.
   */
  def markdown(): DocumentChunker = MarkdownChunker()

  /**
   * Create a semantic chunker using embeddings.
   *
   * Splits text at topic boundaries by analyzing semantic similarity
   * between consecutive sentences. Produces the highest quality chunks
   * but requires an embedding client.
   *
   * @param embeddingClient Client for generating embeddings
   * @param modelConfig Model configuration for embeddings
   * @param similarityThreshold Minimum similarity to stay in same chunk (0.0-1.0, default: 0.5)
   * @param batchSize Number of sentences to embed at once (default: 50)
   */
  def semantic(
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig,
    similarityThreshold: Double = SemanticChunker.DEFAULT_SIMILARITY_THRESHOLD,
    batchSize: Int = SemanticChunker.DEFAULT_BATCH_SIZE
  ): DocumentChunker = SemanticChunker(embeddingClient, modelConfig, similarityThreshold, batchSize)

  /**
   * Create a chunker by strategy name.
   *
   * @param strategy Strategy name: "simple", "sentence", "markdown", "semantic"
   * @return DocumentChunker or None if strategy unknown
   *
   * Note: "semantic" strategy requires an EmbeddingProvider and returns
   * a SentenceChunker as fallback. Use semantic() method for proper semantic chunking.
   */
  def create(strategy: String): Option[DocumentChunker] =
    Strategy.fromString(strategy).map {
      case Strategy.Simple   => simple()
      case Strategy.Sentence => sentence()
      case Strategy.Markdown => markdown()
      case Strategy.Semantic => sentence() // Fallback - semantic requires embedding provider
    }

  /**
   * Create a chunker based on strategy enum.
   *
   * @param strategy Chunking strategy
   * @return DocumentChunker
   */
  def create(strategy: Strategy): DocumentChunker = strategy match {
    case Strategy.Simple   => simple()
    case Strategy.Sentence => sentence()
    case Strategy.Markdown => markdown()
    case Strategy.Semantic => sentence() // Fallback - semantic requires embedding provider
  }

  /**
   * Auto-detect the best chunker based on content.
   *
   * Analyzes the text to determine if it's markdown or plain text,
   * then returns an appropriate chunker.
   *
   * @param text Content to analyze
   * @return Appropriate DocumentChunker
   */
  def auto(text: String): DocumentChunker = {
    val isMarkdown = detectMarkdown(text)

    if (isMarkdown) {
      markdown()
    } else {
      sentence()
    }
  }

  /**
   * Detect if text appears to be markdown.
   */
  private def detectMarkdown(text: String): Boolean = {
    // Check for common markdown patterns
    val hasCodeBlock    = text.contains("```")
    val hasHeading      = text.matches("""(?m)^#{1,6}\s+\S.*""")
    val hasListItems    = text.matches("""(?m)^[\s]*[-*+]\s+\S.*""")
    val hasNumberedList = text.matches("""(?m)^[\s]*\d+\.\s+\S.*""")
    val hasLinks        = text.contains("](")
    val hasImages       = text.contains("![")

    hasCodeBlock || hasHeading || hasListItems || hasNumberedList || hasLinks || hasImages
  }

  /**
   * Get the default chunker (sentence-aware).
   */
  val default: DocumentChunker = sentence()
}
