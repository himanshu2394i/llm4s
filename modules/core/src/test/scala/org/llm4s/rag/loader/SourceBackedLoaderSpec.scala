package org.llm4s.rag.loader

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

class SourceBackedLoaderSpec extends AnyFlatSpec with Matchers {

  /**
   * A simple in-memory document source for testing.
   */
  class InMemoryDocumentSource(documents: Map[String, Array[Byte]]) extends DocumentSource {

    override def listDocuments(): Iterator[Result[DocumentRef]] =
      documents.keys.map { key =>
        Right(
          DocumentRef(
            id = key,
            path = key,
            contentLength = Some(documents(key).length.toLong),
            etag = Some(s"etag-$key")
          )
        )
      }.iterator

    override def readDocument(ref: DocumentRef): Result[RawDocument] =
      documents.get(ref.id) match {
        case Some(content) => Right(RawDocument(ref, content))
        case None =>
          Left(org.llm4s.error.NotFoundError(s"Document not found: ${ref.id}", ref.id))
      }

    override def description: String = s"InMemory(${documents.size} docs)"

    override def estimatedCount: Option[Int] = Some(documents.size)
  }

  "SourceBackedLoader" should "load documents from a DocumentSource" in {
    val docs = Map(
      "doc1.txt" -> "Hello, World!".getBytes(StandardCharsets.UTF_8),
      "doc2.txt" -> "Goodbye, World!".getBytes(StandardCharsets.UTF_8)
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source)

    val results = loader.load().toList

    results.size shouldBe 2
    results.count(_.isSuccess) shouldBe 2

    val contents = results.flatMap(_.toOption).map(_.content)
    contents should contain("Hello, World!")
    contents should contain("Goodbye, World!")
  }

  it should "include metadata from source" in {
    val docs = Map(
      "doc.txt" -> "Content".getBytes(StandardCharsets.UTF_8)
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source)

    val results = loader.load().toList
    val doc     = results.head.toOption.get

    (doc.metadata should contain).key("source")
    (doc.metadata should contain).key("sourceId")
  }

  it should "add additional metadata" in {
    val docs = Map(
      "doc.txt" -> "Content".getBytes(StandardCharsets.UTF_8)
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source).withMetadata(Map("project" -> "test"))

    val results = loader.load().toList
    val doc     = results.head.toOption.get

    doc.metadata.get("project") shouldBe Some("test")
  }

  it should "detect document hints based on extension" in {
    val docs = Map(
      "readme.md" -> "# Title\n\nContent".getBytes(StandardCharsets.UTF_8)
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source)

    val results = loader.load().toList
    val doc     = results.head.toOption.get

    doc.hints shouldBe defined
    // Markdown files should get markdown hints
  }

  it should "include version info from source etag" in {
    val docs = Map(
      "doc.txt" -> "Content".getBytes(StandardCharsets.UTF_8)
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source)

    val results = loader.load().toList
    val doc     = results.head.toOption.get

    doc.version shouldBe defined
    doc.version.get.etag shouldBe Some("etag-doc.txt")
  }

  it should "report estimated count from source" in {
    val docs = Map(
      "doc1.txt" -> "A".getBytes,
      "doc2.txt" -> "B".getBytes,
      "doc3.txt" -> "C".getBytes
    )
    val source = new InMemoryDocumentSource(docs)
    val loader = SourceBackedLoader(source)

    loader.estimatedCount shouldBe Some(3)
  }

  it should "have a descriptive description" in {
    val source = new InMemoryDocumentSource(Map.empty)
    val loader = SourceBackedLoader(source)

    loader.description should include("SourceBackedLoader")
    loader.description should include("InMemory")
  }
}

class DocumentRefSpec extends AnyFlatSpec with Matchers {

  "DocumentRef" should "extract extension from path" in {
    val ref = DocumentRef("id", "path/to/file.pdf")
    ref.extension shouldBe Some("pdf")
  }

  it should "handle no extension" in {
    val ref = DocumentRef("id", "path/to/file")
    ref.extension shouldBe None
  }

  it should "extract filename from path" in {
    val ref = DocumentRef("id", "path/to/document.txt")
    ref.filename shouldBe "document.txt"
  }

  it should "handle path without slashes" in {
    val ref = DocumentRef("id", "document.txt")
    ref.filename shouldBe "document.txt"
  }

  it should "convert to DocumentVersion when etag is present" in {
    val ref = DocumentRef(
      id = "id",
      path = "path",
      etag = Some("abc123"),
      lastModified = Some(1234567890L)
    )

    val version = ref.toVersion
    version shouldBe defined
    version.get.contentHash shouldBe "abc123"
    version.get.timestamp shouldBe Some(1234567890L)
  }

  it should "return None for toVersion when no etag" in {
    val ref = DocumentRef("id", "path")
    ref.toVersion shouldBe None
  }
}
