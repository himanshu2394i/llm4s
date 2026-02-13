package org.llm4s.agent.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory embedding cache.
 */
final class InMemoryEmbeddingCache extends EmbeddingCache {

  private val store =
    new ConcurrentHashMap[String, Array[Float]]()

  override def get(text: String): Option[Array[Float]] =
    Option(store.get(text))

  override def put(text: String, embedding: Array[Float]): Unit =
    store.put(text, embedding)
}
