package org.llm4s.toolapi.builtin.search

import org.llm4s.core.safety.UsingOps.using
import org.llm4s.toolapi._
import upickle.default._

import java.net.{ HttpURLConnection, URI, URLEncoder }
import scala.io.Source
import scala.util.Try

/**
 * A related topic from web search.
 */
case class RelatedTopic(
  text: String,
  url: Option[String]
)

object RelatedTopic {
  implicit val relatedTopicRW: ReadWriter[RelatedTopic] = macroRW[RelatedTopic]
}

/**
 * Web search result.
 */
case class WebSearchResult(
  query: String,
  abstract_ : String,
  abstractSource: String,
  abstractUrl: String,
  answer: String,
  answerType: String,
  relatedTopics: Seq[RelatedTopic],
  infoboxContent: Option[String]
)

object WebSearchResult {
  implicit val webSearchResultRW: ReadWriter[WebSearchResult] = macroRW[WebSearchResult]
}

/**
 * Configuration for web search tool.
 *
 * @param timeoutMs Request timeout in milliseconds.
 * @param maxResults Maximum number of related topics to return.
 * @param safeSearch Whether to enable safe search.
 */
case class WebSearchConfig(
  timeoutMs: Int = 10000,
  maxResults: Int = 10,
  safeSearch: Boolean = true
)

/**
 * Tool for web searching using DuckDuckGo's Instant Answer API.
 *
 * This tool provides quick answers and definitions without requiring an API key.
 * It's best suited for factual queries, definitions, and quick lookups.
 *
 * Note: This uses DuckDuckGo's free Instant Answer API which provides:
 * - Definitions from Wikipedia
 * - Quick facts
 * - Related topics
 * - Disambiguation pages
 *
 * It does NOT provide full web search results (that would require a paid API).
 *
 * @example
 * {{{{
 * import org.llm4s.toolapi.builtin.search._
 *
 * val searchTool = WebSearchTool.create()
 *
 * val tools = new ToolRegistry(Seq(searchTool))
 * agent.run("What is Scala programming language?", tools)
 * }}}}
 */
object WebSearchTool {

  private val DuckDuckGoApiUrl = "https://api.duckduckgo.com/"

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Web search parameters")
    .withProperty(
      Schema.property(
        "query",
        Schema.string("The search query (best for definitions, facts, quick lookups)")
      )
    )

  /**
   * Create a web search tool with the given configuration.
   */
  def create(config: WebSearchConfig = WebSearchConfig()): ToolFunction[Map[String, Any], WebSearchResult] =
    ToolBuilder[Map[String, Any], WebSearchResult](
      name = "web_search",
      description = "Search the web for definitions, facts, and quick answers using DuckDuckGo. " +
        "Best for factual queries and definitions. Does not provide full web search results. " +
        s"Timeout: ${config.timeoutMs}ms.",
      schema = createSchema
    ).withHandler { extractor =>
      for {
        query  <- extractor.getString("query")
        result <- search(query, config)
      } yield result
    }.build()

  /**
   * Default web search tool with standard configuration.
   */
  val tool: ToolFunction[Map[String, Any], WebSearchResult] = create()

  private def search(
    query: String,
    config: WebSearchConfig
  ): Either[String, WebSearchResult] = {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val safeSearch   = if (config.safeSearch) "1" else "-1"
    val url = s"$DuckDuckGoApiUrl?q=$encodedQuery&format=json&no_html=1&skip_disambig=0&t=llm4s&safesearch=$safeSearch"

    Try {
      val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]

      connection.setRequestMethod("GET")
      connection.setConnectTimeout(config.timeoutMs)
      connection.setReadTimeout(config.timeoutMs)
      connection.setRequestProperty("User-Agent", "llm4s-web-search/1.0")

      val responseCode = connection.getResponseCode

      if (responseCode != 200) {
        throw new RuntimeException(s"Search failed with status code: $responseCode")
      }

      val responseBody = using(connection.getInputStream) { is =>
        using(Source.fromInputStream(is, "UTF-8"))(source => source.mkString)
      }

      connection.disconnect()

      // Parse the JSON response
      val json = ujson.read(responseBody)

      val relatedTopics = json.obj
        .get("RelatedTopics")
        .map { topics =>
          topics.arr
            .take(config.maxResults)
            .flatMap { topic =>
              topic.obj.get("Text").map { text =>
                RelatedTopic(
                  text = text.str,
                  url = topic.obj.get("FirstURL").map(_.str)
                )
              }
            }
            .toSeq
        }
        .getOrElse(Seq.empty)

      val infobox = json.obj.get("Infobox").flatMap { infobox =>
        infobox.obj.get("content").map { content =>
          content.arr
            .map { item =>
              val label = item.obj.get("label").map(_.str).getOrElse("")
              val value = item.obj.get("value").map(_.str).getOrElse("")
              s"$label: $value"
            }
            .mkString("\n")
        }
      }

      WebSearchResult(
        query = query,
        abstract_ = json.obj.get("Abstract").map(_.str).getOrElse(""),
        abstractSource = json.obj.get("AbstractSource").map(_.str).getOrElse(""),
        abstractUrl = json.obj.get("AbstractURL").map(_.str).getOrElse(""),
        answer = json.obj.get("Answer").map(_.str).getOrElse(""),
        answerType = json.obj.get("AnswerType").map(_.str).getOrElse(""),
        relatedTopics = relatedTopics,
        infoboxContent = infobox
      )
    }.toEither.left.map(e => s"Web search failed: ${e.getMessage}")
  }
}
