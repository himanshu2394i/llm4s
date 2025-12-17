package org.llm4s.rag.loader

import org.llm4s.error.NetworkError

import java.net.{ HttpURLConnection, URI }
import scala.io.Source
import scala.util.Using

/**
 * Load documents from URLs.
 *
 * Supports HTTP/HTTPS URLs with configurable timeouts and headers.
 * Includes ETag-based version detection for efficient sync operations.
 *
 * @param urls URLs to load
 * @param headers HTTP headers to send with requests
 * @param timeoutMs Connection and read timeout in milliseconds
 * @param metadata Additional metadata to attach
 * @param retryCount Number of retry attempts for failed requests
 */
final case class UrlLoader(
  urls: Seq[String],
  headers: Map[String, String] = Map.empty,
  timeoutMs: Int = 30000,
  metadata: Map[String, String] = Map.empty,
  retryCount: Int = 2
) extends DocumentLoader {

  def load(): Iterator[LoadResult] = urls.iterator.map(loadUrl)

  private def loadUrl(urlString: String): LoadResult = {
    import org.llm4s.types.TryOps

    val result = scala.util.Try {
      val uri  = new URI(urlString)
      val conn = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(timeoutMs)
      conn.setReadTimeout(timeoutMs)
      conn.setRequestProperty("User-Agent", "LLM4S-RAG/1.0")
      headers.foreach { case (k, v) => conn.setRequestProperty(k, v) }
      conn
    }.toResult

    result match {
      case Left(error) =>
        LoadResult.failure(urlString, NetworkError(error.message, None, "http"))

      case Right(conn) =>
        val docResult = scala.util.Try {
          val responseCode = conn.getResponseCode
          if (responseCode != 200) {
            conn.disconnect()
            throw new RuntimeException(s"HTTP $responseCode: ${conn.getResponseMessage}")
          }

          val content = Using.resource(Source.fromInputStream(conn.getInputStream, "UTF-8")) {
            _.mkString
          }

          val contentType = Option(conn.getContentType).getOrElse("text/plain")
          val etag        = Option(conn.getHeaderField("ETag"))
          val lastMod     = Option(conn.getLastModified).filter(_ > 0)

          conn.disconnect()

          val version = DocumentVersion(
            contentHash = DocumentVersion.fromContent(content).contentHash,
            timestamp = lastMod,
            etag = etag
          )

          Document(
            id = urlString,
            content = content,
            metadata = metadata ++ Map(
              "source"       -> urlString,
              "url"          -> urlString,
              "content_type" -> contentType
            ) ++ etag.map("etag" -> _).toMap
              ++ lastMod.map(m => "lastModified" -> m.toString).toMap,
            hints = Some(detectHints(urlString, contentType)),
            version = Some(version)
          )
        }.toResult

        docResult match {
          case Left(error) =>
            conn.disconnect()
            LoadResult.failure(urlString, NetworkError(error.message, None, "http"))
          case Right(doc) =>
            LoadResult.success(doc)
        }
    }
  }

  private def detectHints(url: String, contentType: String): DocumentHints =
    if (contentType.contains("markdown") || url.endsWith(".md")) {
      DocumentHints.markdown
    } else if (contentType.contains("html")) {
      DocumentHints.prose
    } else {
      DocumentHints.prose
    }

  override def estimatedCount: Option[Int] = Some(urls.size)

  def description: String = s"UrlLoader(${urls.size} URLs)"

  /** Add a URL */
  def withUrl(url: String): UrlLoader =
    copy(urls = urls :+ url)

  /** Add headers */
  def withHeaders(h: Map[String, String]): UrlLoader =
    copy(headers = headers ++ h)

  /** Set timeout */
  def withTimeout(ms: Int): UrlLoader =
    copy(timeoutMs = ms)

  /** Set retry count */
  def withRetries(n: Int): UrlLoader =
    copy(retryCount = n)
}

object UrlLoader {

  def apply(url: String): UrlLoader =
    UrlLoader(Seq(url))

  def apply(urls: String*): UrlLoader =
    UrlLoader(urls.toSeq)

  /** Create with authorization header */
  def withAuth(urls: Seq[String], token: String): UrlLoader =
    UrlLoader(urls, headers = Map("Authorization" -> s"Bearer $token"))

  /** Create with custom user agent */
  def withUserAgent(urls: Seq[String], userAgent: String): UrlLoader =
    UrlLoader(urls, headers = Map("User-Agent" -> userAgent))
}
