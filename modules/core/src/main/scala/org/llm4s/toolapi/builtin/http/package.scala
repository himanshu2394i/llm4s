package org.llm4s.toolapi.builtin

/**
 * HTTP tools for making web requests.
 *
 * These tools provide safe HTTP access with configurable
 * domain restrictions and method limitations.
 *
 * == Configuration ==
 *
 * All HTTP tools accept [[HttpConfig]] for request configuration:
 *
 * - Domain allowlists and blocklists for security
 * - Allowed HTTP methods
 * - Response size limits
 * - Timeout configuration
 *
 * == Available Tools ==
 *
 * - [[HTTPTool]]: Make HTTP requests (GET, POST, PUT, DELETE, etc.)
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.http._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Read-only HTTP tool (GET/HEAD only)
 * val readOnlyTool = HTTPTool.create(HttpConfig.readOnly())
 *
 * // Restricted to specific domains
 * val restrictedTool = HTTPTool.create(HttpConfig.restricted(
 *   Seq("api.example.com", "data.example.org")
 * ))
 *
 * // Full access with custom timeout
 * val fullTool = HTTPTool.create(HttpConfig(
 *   timeoutMs = 60000,
 *   maxResponseSize = 50 * 1024 * 1024
 * ))
 *
 * val tools = new ToolRegistry(Seq(restrictedTool))
 * }}}
 */
package object http {

  /**
   * All HTTP tools with default configuration.
   */
  val allTools: Seq[org.llm4s.toolapi.ToolFunction[_, _]] = Seq(
    HTTPTool.tool
  )
}
