package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

import scala.util.Try

/**
 * Qdrant-based persistent document registry.
 *
 * Stores document version information in a Qdrant collection for
 * persistent tracking alongside Qdrant vector storage.
 *
 * Features:
 * - Cloud-native storage alongside Qdrant vector store
 * - Uses payload-only points (no vectors needed)
 * - Efficient lookups by document ID
 * - Timestamp tracking for last update
 *
 * @param baseUrl Base URL for Qdrant API (e.g., "http://localhost:6333")
 * @param collectionName Name of the collection to use
 * @param apiKey Optional API key for authentication
 */
final class QdrantDocumentRegistry private (
  val baseUrl: String,
  val collectionName: String,
  private val apiKey: Option[String]
) extends DocumentRegistry {

  private val collectionsUrl = s"$baseUrl/collections/$collectionName"
  private val pointsUrl      = s"$collectionsUrl/points"

  // Initialize collection on creation
  ensureCollection()

  private def ensureCollection(): Unit = {
    val checkResult = httpGet(collectionsUrl)
    if (checkResult.isLeft) {
      // Create collection with minimal vector config
      // We'll store registry data as payload, using a dummy 1D vector
      val body = ujson.Obj(
        "vectors" -> ujson.Obj(
          "size"     -> 1,
          "distance" -> "Cosine"
        )
      )
      httpPut(collectionsUrl, body)
    }
    ()
  }

  override def getVersion(docId: String): Result[Option[DocumentVersion]] =
    Try {
      httpGet(s"$pointsUrl/$docId?with_payload=true").map { response =>
        val result = response("result")
        if (result.isNull) None
        else payloadToVersion(result("payload").obj)
      }
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"Failed to get version: ${e.getMessage}"))
      .flatMap {
        case Left(err) if err.message.contains("Not found") => Right(None)
        case other                                          => other
      }

  override def register(docId: String, version: DocumentVersion): Result[Unit] =
    Try {
      val point = ujson.Obj(
        "id"      -> docId,
        "vector"  -> ujson.Arr(0.0), // Dummy vector, we only use payload
        "payload" -> versionToPayload(docId, version)
      )

      val body = ujson.Obj("points" -> ujson.Arr(point))
      httpPut(s"$pointsUrl?wait=true", body)
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"Failed to register: ${e.getMessage}"))
      .flatMap(identity)

  override def unregister(docId: String): Result[Unit] =
    Try {
      val body = ujson.Obj(
        "points" -> ujson.Arr(docId)
      )
      httpPost(s"$pointsUrl/delete?wait=true", body).map(_ => ())
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"Failed to unregister: ${e.getMessage}"))
      .flatMap(identity)

  override def allDocumentIds(): Result[Set[String]] =
    Try {
      var ids                    = Set.empty[String]
      var offset: Option[String] = None
      var hasMore                = true

      while (hasMore) {
        val body = ujson.Obj(
          "limit"        -> 100,
          "with_payload" -> false,
          "with_vector"  -> false
        )
        offset.foreach(o => body("offset") = o)

        val result = httpPost(s"$pointsUrl/scroll", body)
        result match {
          case Right(response) =>
            val points = response("result")("points").arr
            if (points.isEmpty) {
              hasMore = false
            } else {
              ids ++= points.map(p => p("id").str).toSet
              offset =
                response("result").obj.get("next_page_offset").flatMap(v => if (v.isNull) None else Some(v.str))
              hasMore = offset.isDefined
            }
          case Left(_) =>
            hasMore = false
        }
      }
      ids
    }.toEither.left.map(e => ProcessingError("qdrant-registry", s"Failed to get all IDs: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    Try {
      // Delete all points in the collection
      val body = ujson.Obj(
        "filter" -> ujson.Obj() // Empty filter matches all
      )
      httpPost(s"$pointsUrl/delete?wait=true", body).map(_ => ())
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"Failed to clear: ${e.getMessage}"))
      .flatMap(identity)

  override def count(): Result[Int] =
    Try {
      val body = ujson.Obj("exact" -> true)
      httpPost(s"$pointsUrl/count", body).map(response => response("result")("count").num.toInt)
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"Failed to count: ${e.getMessage}"))
      .flatMap(identity)

  /**
   * Close the registry (no-op for REST API).
   */
  def close(): Unit = {
    // No persistent connection to close for REST API
  }

  // ============================================================
  // HTTP Helpers
  // ============================================================

  private def httpGet(url: String): Result[ujson.Value] =
    Try {
      val response = requests.get(
        url,
        headers = authHeaders,
        check = false
      )
      handleResponse(response)
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"HTTP GET failed: ${e.getMessage}"))
      .flatMap(identity)

  private def httpPost(url: String, body: ujson.Value): Result[ujson.Value] =
    Try {
      val response = requests.post(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/json"),
        data = ujson.write(body),
        check = false
      )
      handleResponse(response)
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"HTTP POST failed: ${e.getMessage}"))
      .flatMap(identity)

  private def httpPut(url: String, body: ujson.Value): Result[Unit] =
    Try {
      val response = requests.put(
        url,
        headers = authHeaders ++ Map("Content-Type" -> "application/json"),
        data = ujson.write(body),
        check = false
      )
      if (response.statusCode >= 200 && response.statusCode < 300) Right(())
      else Left(ProcessingError("qdrant-registry", s"HTTP PUT failed: ${response.statusCode} - ${response.text()}"))
    }.toEither.left
      .map(e => ProcessingError("qdrant-registry", s"HTTP PUT failed: ${e.getMessage}"))
      .flatMap(identity)

  private def handleResponse(response: requests.Response): Result[ujson.Value] =
    if (response.statusCode >= 200 && response.statusCode < 300) {
      Right(ujson.read(response.text()))
    } else if (response.statusCode == 404) {
      Left(ProcessingError("qdrant-registry", "Not found"))
    } else {
      Left(ProcessingError("qdrant-registry", s"HTTP error: ${response.statusCode} - ${response.text()}"))
    }

  private def authHeaders: Map[String, String] =
    apiKey.map(key => Map("api-key" -> key)).getOrElse(Map.empty)

  // ============================================================
  // Conversion Helpers
  // ============================================================

  private def versionToPayload(docId: String, version: DocumentVersion): ujson.Obj = {
    val payload = ujson.Obj(
      "doc_id"        -> docId,
      "content_hash"  -> version.contentHash,
      "registered_at" -> System.currentTimeMillis()
    )
    version.timestamp.foreach(ts => payload("timestamp") = ts)
    version.etag.foreach(e => payload("etag") = e)
    payload
  }

  private def payloadToVersion(payload: ujson.Obj): Option[DocumentVersion] = {
    val hash = payload.value.get("content_hash").map(_.str)
    hash.map { h =>
      DocumentVersion(
        contentHash = h,
        timestamp = payload.value.get("timestamp").map(_.num.toLong),
        etag = payload.value.get("etag").flatMap(v => if (v.isNull) None else Some(v.str))
      )
    }
  }
}

object QdrantDocumentRegistry {

  /**
   * Configuration for QdrantDocumentRegistry.
   *
   * @param host Qdrant host
   * @param port Qdrant port (default: 6333)
   * @param collectionName Collection name for registry
   * @param apiKey Optional API key
   * @param https Use HTTPS (default: false for local)
   */
  final case class Config(
    host: String = "localhost",
    port: Int = 6333,
    collectionName: String = "document_registry",
    apiKey: Option[String] = None,
    https: Boolean = false
  ) {
    def baseUrl: String = {
      val protocol = if (https) "https" else "http"
      s"$protocol://$host:$port"
    }
  }

  /**
   * Create a QdrantDocumentRegistry from configuration.
   *
   * @param config The registry configuration
   * @return The registry or error
   */
  def apply(config: Config): Result[QdrantDocumentRegistry] =
    Try {
      new QdrantDocumentRegistry(config.baseUrl, config.collectionName, config.apiKey)
    }.toEither.left.map(e => ProcessingError("qdrant-registry", s"Failed to create registry: ${e.getMessage}"))

  /**
   * Create a QdrantDocumentRegistry from base URL.
   *
   * @param baseUrl Base URL for Qdrant API
   * @param collectionName Collection name for registry
   * @param apiKey Optional API key
   * @return The registry or error
   */
  def apply(
    baseUrl: String,
    collectionName: String = "document_registry",
    apiKey: Option[String] = None
  ): Result[QdrantDocumentRegistry] =
    Try {
      new QdrantDocumentRegistry(baseUrl, collectionName, apiKey)
    }.toEither.left.map(e => ProcessingError("qdrant-registry", s"Failed to create registry: ${e.getMessage}"))

  /**
   * Create a QdrantDocumentRegistry with default local settings.
   *
   * Connects to localhost:6333.
   *
   * @param collectionName Collection name (default: "document_registry")
   * @return The registry or error
   */
  def local(collectionName: String = "document_registry"): Result[QdrantDocumentRegistry] =
    apply(Config(collectionName = collectionName))

  /**
   * Create a QdrantDocumentRegistry for Qdrant Cloud.
   *
   * @param cloudUrl Qdrant Cloud URL
   * @param apiKey API key for authentication
   * @param collectionName Collection name
   * @return The registry or error
   */
  def cloud(
    cloudUrl: String,
    apiKey: String,
    collectionName: String = "document_registry"
  ): Result[QdrantDocumentRegistry] =
    apply(cloudUrl, collectionName, Some(apiKey))

  /**
   * Create a QdrantDocumentRegistry that shares connection settings with a QdrantVectorStore.
   *
   * Uses the same Qdrant instance but a separate collection for registry.
   *
   * @param vectorStoreConfig QdrantVectorStore configuration
   * @param collectionName Collection name for registry (default: "document_registry")
   * @return The registry or error
   */
  def forVectorStore(
    vectorStoreConfig: org.llm4s.vectorstore.QdrantVectorStore.Config,
    collectionName: String = "document_registry"
  ): Result[QdrantDocumentRegistry] =
    apply(
      Config(
        host = vectorStoreConfig.host,
        port = vectorStoreConfig.port,
        collectionName = collectionName,
        apiKey = vectorStoreConfig.apiKey,
        https = vectorStoreConfig.https
      )
    )
}
