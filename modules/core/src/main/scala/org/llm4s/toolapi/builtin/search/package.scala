package org.llm4s.toolapi.builtin

/**
 * Search tools for web searches and lookups.
 *
 * These tools provide web search capabilities using free APIs
 * that don't require API keys.
 *
 * == Available Tools ==
 *
 * - [[WebSearchTool]]: Search using DuckDuckGo Instant Answer API
 *   - Best for definitions, facts, quick lookups
 *   - No API key required
 *   - Returns abstracts, related topics, and infobox data
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.search._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Default search tool
 * val searchTool = WebSearchTool.create()
 *
 * // Custom configuration
 * val customSearch = WebSearchTool.create(WebSearchConfig(
 *   timeoutMs = 15000,
 *   maxResults = 5
 * ))
 *
 * val tools = new ToolRegistry(Seq(searchTool))
 * }}}
 */
package object search {

  /**
   * All search tools with default configuration.
   */
  val allTools: Seq[org.llm4s.toolapi.ToolFunction[_, _]] = Seq(
    WebSearchTool.tool
  )
}
