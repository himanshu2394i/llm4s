package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite

class CachedEmbeddingServiceSpec extends AnyFunSuite {

  class CountingEmbeddingService extends EmbeddingService {

    var singleCalls = 0
    var batchCalls  = 0

    override def dimensions: Int = 3

    override def embed(text: String): Result[Array[Float]] = {
      singleCalls += 1
      Right(Array(1f, 2f, 3f))
    }

    override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = {
      batchCalls += 1
      Right(texts.map(_ => Array(1f, 2f, 3f)))
    }
  }

  test("single embed should cache result") {
    val inner  = new CountingEmbeddingService
    val cache  = new InMemoryEmbeddingCache
    val cached = new CachedEmbeddingService(inner, cache)

    cached.embed("hello")
    cached.embed("hello")

    assert(inner.singleCalls == 1)
  }

  test("batch embed should only call inner for missing texts") {
    val inner  = new CountingEmbeddingService
    val cache  = new InMemoryEmbeddingCache
    val cached = new CachedEmbeddingService(inner, cache)

    cached.embed("a")
    cached.embedBatch(Seq("a", "b"))

    assert(inner.batchCalls == 1)
  }
}
