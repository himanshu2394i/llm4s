package org.llm4s.toolapi

/**
 * Strategy for executing multiple tool calls.
 *
 * When an LLM requests multiple tool calls, they can be executed
 * either sequentially (one at a time) or in parallel (simultaneously).
 *
 * @example
 * {{{
 * // Execute tools in parallel
 * agent.runWithStrategy(
 *   query = "Get weather in London, Paris, and Tokyo",
 *   tools = weatherTools,
 *   toolExecutionStrategy = ToolExecutionStrategy.Parallel
 * )
 *
 * // Limit concurrency to 2 at a time
 * agent.runWithStrategy(
 *   query = "Search 10 different topics",
 *   tools = searchTools,
 *   toolExecutionStrategy = ToolExecutionStrategy.ParallelWithLimit(2)
 * )
 * }}}
 */
sealed trait ToolExecutionStrategy

object ToolExecutionStrategy {

  /**
   * Execute tools one at a time, in order.
   *
   * This is the safest strategy and the default behavior.
   * Use when:
   * - Tools have dependencies on each other
   * - Order of execution matters
   * - Debugging tool behavior
   */
  case object Sequential extends ToolExecutionStrategy

  /**
   * Execute all tools simultaneously.
   *
   * Best for independent, IO-bound tools like:
   * - Multiple API calls
   * - Database queries
   * - File operations
   *
   * Caution: May cause rate limiting with external APIs.
   */
  case object Parallel extends ToolExecutionStrategy

  /**
   * Execute tools in parallel with a concurrency limit.
   *
   * Balances performance with resource constraints.
   * Use when:
   * - External APIs have rate limits
   * - System has limited resources
   * - Want parallel execution but controlled
   *
   * @param maxConcurrency Maximum number of tools executing simultaneously
   */
  final case class ParallelWithLimit(maxConcurrency: Int) extends ToolExecutionStrategy {
    require(maxConcurrency > 0, "maxConcurrency must be positive")
  }

  /**
   * Default strategy: Sequential execution.
   */
  val default: ToolExecutionStrategy = Sequential
}
