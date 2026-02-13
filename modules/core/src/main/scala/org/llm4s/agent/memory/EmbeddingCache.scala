package org.llm4s.agent.memory

/**
 * Abstraction for caching embeddings by text input.
 */
trait EmbeddingCache {

  /**
   * Retrieve cached embedding for a given text.
   */
  def get(text: String): Option[Array[Float]]

  /**
   * Store embedding for a given text.
   */
  def put(text: String, embedding: Array[Float]): Unit
}
