package org.llm4s.config.model

/**
 * Root configuration structure matching reference.conf layout.
 *
 * This is the typed representation of the llm4s configuration namespace.
 * PureConfig automatically derives readers for these case classes.
 */
case class LLM4SConfig(
  llm: LLMSettings = LLMSettings(),
  openai: OpenAISettings = OpenAISettings(),
  azure: AzureSettings = AzureSettings(),
  anthropic: AnthropicSettings = AnthropicSettings(),
  ollama: OllamaSettings = OllamaSettings(),
  tracing: TracingSettings = TracingSettings(),
  embeddings: EmbeddingsSettings = EmbeddingsSettings(),
  workspace: WorkspaceSettings = WorkspaceSettings()
)

/**
 * Primary model selection settings.
 * Example: "openai/gpt-4o", "anthropic/claude-3-7-sonnet-latest"
 */
case class LLMSettings(
  model: Option[String] = None
)

/**
 * OpenAI provider settings.
 * Also used for OpenRouter via baseUrl override.
 */
case class OpenAISettings(
  baseUrl: String = "https://api.openai.com/v1",
  apiKey: Option[String] = None,
  organization: Option[String] = None
)

/**
 * Azure OpenAI provider settings.
 */
case class AzureSettings(
  endpoint: Option[String] = None,
  apiKey: Option[String] = None,
  apiVersion: Option[String] = None
)

/**
 * Anthropic provider settings.
 */
case class AnthropicSettings(
  baseUrl: String = "https://api.anthropic.com",
  apiKey: Option[String] = None
)

/**
 * Ollama (local models) provider settings.
 */
case class OllamaSettings(
  baseUrl: Option[String] = None
)

/**
 * Tracing configuration.
 */
case class TracingSettings(
  mode: String = "console",
  langfuse: LangfuseSettings = LangfuseSettings()
)

/**
 * Langfuse tracing settings.
 */
case class LangfuseSettings(
  url: Option[String] = None,
  publicKey: Option[String] = None,
  secretKey: Option[String] = None,
  env: Option[String] = None,
  release: Option[String] = None,
  version: Option[String] = None
)

/**
 * Embeddings configuration.
 */
case class EmbeddingsSettings(
  provider: Option[String] = None,
  inputPath: Option[String] = None,
  query: Option[String] = None,
  openai: OpenAIEmbeddingSettings = OpenAIEmbeddingSettings(),
  voyage: VoyageEmbeddingSettings = VoyageEmbeddingSettings(),
  chunking: ChunkingSettings = ChunkingSettings()
)

/**
 * OpenAI embedding settings.
 */
case class OpenAIEmbeddingSettings(
  baseUrl: Option[String] = None,
  model: Option[String] = None
)

/**
 * Voyage AI embedding settings.
 */
case class VoyageEmbeddingSettings(
  apiKey: Option[String] = None,
  baseUrl: Option[String] = None,
  model: Option[String] = None
)

/**
 * Text chunking configuration for embeddings.
 */
case class ChunkingSettings(
  size: Option[Int] = None,
  overlap: Option[Int] = None,
  enabled: Option[Boolean] = None
)

/**
 * Workspace path configuration.
 */
case class WorkspaceSettings(
  path: Option[String] = None
)
