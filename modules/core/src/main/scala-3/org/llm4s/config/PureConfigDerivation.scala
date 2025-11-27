package org.llm4s.config

import pureconfig.*
import org.llm4s.config.model.*

/**
 * PureConfig readers for Scala 3 using automatic derivation.
 *
 * This object provides ConfigReader instances for all configuration case classes.
 * Uses implicit vals (which work for both implicit and given resolution in Scala 3).
 * Uses default kebab-case -> camelCase field mapping.
 */
object PureConfigDerivation {

  // Explicit derivation for all config case classes
  // Order matters: nested types must be derived before containing types
  implicit val llmSettingsReader: ConfigReader[LLMSettings]                         = ConfigReader.derived
  implicit val openAISettingsReader: ConfigReader[OpenAISettings]                   = ConfigReader.derived
  implicit val azureSettingsReader: ConfigReader[AzureSettings]                     = ConfigReader.derived
  implicit val anthropicSettingsReader: ConfigReader[AnthropicSettings]             = ConfigReader.derived
  implicit val ollamaSettingsReader: ConfigReader[OllamaSettings]                   = ConfigReader.derived
  implicit val langfuseSettingsReader: ConfigReader[LangfuseSettings]               = ConfigReader.derived
  implicit val tracingSettingsReader: ConfigReader[TracingSettings]                 = ConfigReader.derived
  implicit val openAIEmbeddingSettingsReader: ConfigReader[OpenAIEmbeddingSettings] = ConfigReader.derived
  implicit val voyageEmbeddingSettingsReader: ConfigReader[VoyageEmbeddingSettings] = ConfigReader.derived
  implicit val chunkingSettingsReader: ConfigReader[ChunkingSettings]               = ConfigReader.derived
  implicit val embeddingsSettingsReader: ConfigReader[EmbeddingsSettings]           = ConfigReader.derived
  implicit val workspaceSettingsReader: ConfigReader[WorkspaceSettings]             = ConfigReader.derived
  implicit val llm4sConfigReader: ConfigReader[LLM4SConfig]                         = ConfigReader.derived
}
