package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.builtin.core._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.http._
import org.llm4s.toolapi.builtin.search._
import org.llm4s.toolapi.builtin.shell._

/**
 * Aggregator for built-in tools with convenient factory methods.
 *
 * Provides pre-configured tool sets for common use cases:
 *
 * - `safe()`: Core utilities, web search, and read-only HTTP (no file or shell access)
 * - `withFiles()`: Safe tools plus read-only file system access
 * - `development()`: All tools including shell with common dev commands
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.ToolRegistry
 * import org.llm4s.toolapi.builtin.BuiltinTools
 *
 * // Safe tools for production use
 * val safeRegistry = new ToolRegistry(BuiltinTools.safe())
 *
 * // Development tools with file and shell access
 * val devRegistry = new ToolRegistry(BuiltinTools.development(
 *   workingDirectory = Some("/home/user/project")
 * ))
 * }}}
 */
object BuiltinTools {

  /**
   * Core utility tools (always safe, no external dependencies).
   *
   * Includes:
   * - DateTimeTool: Get current date/time
   * - CalculatorTool: Math operations
   * - UUIDTool: Generate UUIDs
   * - JSONTool: Parse/format/query JSON
   */
  def core: Seq[ToolFunction[_, _]] = Seq(
    DateTimeTool.tool,
    CalculatorTool.tool,
    UUIDTool.tool,
    JSONTool.tool
  )

  /**
   * Safe tools for production use.
   *
   * Includes:
   * - All core utilities
   * - Web search (DuckDuckGo, no API key required)
   * - Read-only HTTP with localhost blocked
   *
   * Does NOT include:
   * - File system access
   * - Shell access
   */
  def safe(httpConfig: HttpConfig = HttpConfig.readOnly()): Seq[ToolFunction[_, _]] =
    core ++ Seq(
      WebSearchTool.tool,
      HTTPTool.create(httpConfig)
    )

  /**
   * Safe tools plus read-only file system access.
   *
   * Includes:
   * - All safe tools
   * - Read-only file system tools (read, list, info)
   *
   * Does NOT include:
   * - File writing
   * - Shell access
   */
  def withFiles(
    fileConfig: FileConfig = FileConfig(),
    httpConfig: HttpConfig = HttpConfig.readOnly()
  ): Seq[ToolFunction[_, _]] =
    safe(httpConfig) ++ Seq(
      ReadFileTool.create(fileConfig),
      ListDirectoryTool.create(fileConfig),
      FileInfoTool.create(fileConfig)
    )

  /**
   * Development tools with full read/write and shell access.
   *
   * Includes:
   * - All core utilities
   * - Web search
   * - Full HTTP access
   * - File system read/write
   * - Shell with common dev commands
   *
   * WARNING: These tools have significant access to the system.
   * Use only in trusted development environments.
   *
   * @param workingDirectory Optional working directory for file/shell operations
   * @param fileAllowedPaths Allowed paths for file write operations
   */
  def development(
    workingDirectory: Option[String] = None,
    fileAllowedPaths: Seq[String] = Seq("/tmp")
  ): Seq[ToolFunction[_, _]] = {
    val fileConfig = FileConfig(
      allowedPaths = workingDirectory.map(Seq(_))
    )
    val writeConfig = WriteConfig(
      allowedPaths = fileAllowedPaths ++ workingDirectory.toSeq,
      allowOverwrite = true
    )
    val shellConfig = ShellConfig.development(workingDirectory)

    core ++ Seq(
      WebSearchTool.tool,
      HTTPTool.tool,
      ReadFileTool.create(fileConfig),
      ListDirectoryTool.create(fileConfig),
      FileInfoTool.create(fileConfig),
      WriteFileTool.create(writeConfig),
      ShellTool.create(shellConfig)
    )
  }

  /**
   * Custom tool set with full configuration control.
   *
   * @param fileConfig Configuration for read operations
   * @param writeConfig Optional write configuration (if None, write tool is not included)
   * @param httpConfig HTTP configuration
   * @param shellConfig Optional shell configuration (if None, shell tool is not included)
   * @param includeSearch Whether to include web search
   */
  def custom(
    fileConfig: Option[FileConfig] = None,
    writeConfig: Option[WriteConfig] = None,
    httpConfig: Option[HttpConfig] = None,
    shellConfig: Option[ShellConfig] = None,
    includeSearch: Boolean = true
  ): Seq[ToolFunction[_, _]] = {
    var tools: Seq[ToolFunction[_, _]] = core

    if (includeSearch) {
      tools = tools :+ WebSearchTool.tool
    }

    httpConfig.foreach(cfg => tools = tools :+ HTTPTool.create(cfg))

    fileConfig.foreach { cfg =>
      tools = tools ++ Seq(
        ReadFileTool.create(cfg),
        ListDirectoryTool.create(cfg),
        FileInfoTool.create(cfg)
      )
    }

    writeConfig.foreach(cfg => tools = tools :+ WriteFileTool.create(cfg))

    shellConfig.foreach(cfg => tools = tools :+ ShellTool.create(cfg))

    tools
  }
}
