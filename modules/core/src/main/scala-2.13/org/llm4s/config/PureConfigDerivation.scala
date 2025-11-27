package org.llm4s.config

import pureconfig._
import pureconfig.generic.semiauto._
import org.llm4s.config.model._

/**
 * PureConfig readers for Scala 2.13 using semi-automatic derivation.
 *
 * This object provides implicit ConfigReader instances for all configuration
 * case classes. Uses semiauto derivation which follows PureConfig's default
 * kebab-case to camelCase mapping.
 */
object PureConfigDerivation {

  // Semi-automatic derivation for all config case classes
  // Order matters: nested types must be derived before containing types
  // Uses default kebab-case -> camelCase field mapping
  implicit val llmSettingsReader: ConfigReader[LLMSettings]               = deriveReader[LLMSettings]
  implicit val openAISettingsReader: ConfigReader[OpenAISettings]         = deriveReader[OpenAISettings]
  implicit val azureSettingsReader: ConfigReader[AzureSettings]           = deriveReader[AzureSettings]
  implicit val anthropicSettingsReader: ConfigReader[AnthropicSettings]   = deriveReader[AnthropicSettings]
  implicit val ollamaSettingsReader: ConfigReader[OllamaSettings]         = deriveReader[OllamaSettings]
  implicit val langfuseSettingsReader: ConfigReader[LangfuseSettings]     = deriveReader[LangfuseSettings]
  implicit val tracingSettingsReader: ConfigReader[TracingSettings]       = deriveReader[TracingSettings]
  implicit val openAIEmbeddingSettingsReader: ConfigReader[OpenAIEmbeddingSettings] =
    deriveReader[OpenAIEmbeddingSettings]
  implicit val voyageEmbeddingSettingsReader: ConfigReader[VoyageEmbeddingSettings] =
    deriveReader[VoyageEmbeddingSettings]
  implicit val chunkingSettingsReader: ConfigReader[ChunkingSettings]     = deriveReader[ChunkingSettings]
  implicit val embeddingsSettingsReader: ConfigReader[EmbeddingsSettings] = deriveReader[EmbeddingsSettings]
  implicit val workspaceSettingsReader: ConfigReader[WorkspaceSettings]   = deriveReader[WorkspaceSettings]
  implicit val llm4sConfigReader: ConfigReader[LLM4SConfig]               = deriveReader[LLM4SConfig]
}
