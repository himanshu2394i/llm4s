package org.llm4s.toolapi.builtin

/**
 * Core utility tools that require no external dependencies or API keys.
 *
 * These tools provide common functionality for agent workflows:
 *
 * - [[DateTimeTool]]: Get current date/time in any timezone
 * - [[CalculatorTool]]: Perform mathematical calculations
 * - [[UUIDTool]]: Generate unique identifiers
 * - [[JSONTool]]: Parse, format, and query JSON data
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.core._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * val coreTools = new ToolRegistry(Seq(
 *   DateTimeTool.tool,
 *   CalculatorTool.tool,
 *   UUIDTool.tool,
 *   JSONTool.tool
 * ))
 * }}}
 */
package object core {

  /**
   * All core utility tools combined into a sequence.
   */
  val allTools: Seq[org.llm4s.toolapi.ToolFunction[_, _]] = Seq(
    DateTimeTool.tool,
    CalculatorTool.tool,
    UUIDTool.tool,
    JSONTool.tool
  )
}
