package org.llm4s.agent.memory

import org.llm4s.types.Result

/**
 * EmbeddingService wrapper that adds caching.
 *
 * @param inner Underlying embedding service
 * @param cache Cache implementation
 */
final class CachedEmbeddingService(
  inner: EmbeddingService,
  cache: EmbeddingCache
) extends EmbeddingService {

  override def dimensions: Int =
    inner.dimensions

  override def embed(text: String): Result[Array[Float]] =
    cache.get(text) match {
      case Some(embedding) =>
        Right(embedding)

      case None =>
        inner.embed(text).map { embedding =>
          cache.put(text, embedding)
          embedding
        }
    }

  override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
    if (texts.isEmpty) {
      Right(Seq.empty)
    } else {
      val missingTexts =
        texts.filter(text => cache.get(text).isEmpty)

      if (missingTexts.isEmpty) {
        Right(texts.map(text => cache.get(text).get))
      } else {
        inner.embedBatch(missingTexts).map { newEmbeddings =>
          missingTexts.zip(newEmbeddings).foreach { case (text, embedding) =>
            cache.put(text, embedding)
          }

          texts.map(text => cache.get(text).get)
        }
      }
    }
}
