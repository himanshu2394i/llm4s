package org.llm4s.chunking

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
      case Strategy.Markdown => sentence() // TODO: Implement MarkdownChunker
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
    case Strategy.Markdown => sentence() // TODO: Implement MarkdownChunker
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
      // TODO: Use MarkdownChunker when implemented
      sentence()
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
