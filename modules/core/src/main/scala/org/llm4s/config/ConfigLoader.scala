package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.{ Config => TypesafeConfig, ConfigFactory }
// scalafix:on DisableSyntax.NoConfigFactory
import pureconfig.ConfigSource
import org.llm4s.config.model._
import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{
  ProviderConfig,
  OpenAIConfig,
  AzureConfig,
  AnthropicConfig,
  OllamaConfig,
  EmbeddingProviderConfig,
  LangfuseConfig => LFConfig,
  TracingSettings => TSSettings
}
import org.llm4s.trace.TracingMode
import org.llm4s.config.{ ConfigReader => LLM4SConfigReader }

/**
 * Type-safe configuration loader using PureConfig.
 *
 * Provides type-safe configuration loading with:
 * - Automatic case class derivation
 * - Environment variable support via reference.conf ${?ENV_VAR} syntax
 * - Excellent error messages with provider-specific guidance
 * - Result[A] error handling (no exceptions)
 *
 * Usage:
 * {{{
 * val config: Result[LLM4SConfig] = ConfigLoader.load()
 * config.map { c =>
 *   println(c.openai.apiKey)
 *   println(c.llm.model)
 * }
 * }}}
 */
object ConfigLoader {

  // Import PureConfig readers from version-specific derivation
  // Uses wildcard import to support both Scala 2.13 (implicits) and Scala 3 (givens)
  import PureConfigDerivation._

  /**
   * Load the complete LLM4S configuration from default sources.
   * Honors system properties (-D), environment variables, application.conf, and reference.conf.
   */
  def load(): Result[LLM4SConfig] = {
    // scalafix:off DisableSyntax.NoConfigFactory
    ConfigFactory.invalidateCaches()
    val config = ConfigFactory.load()
    // scalafix:on DisableSyntax.NoConfigFactory
    loadFromConfig(config)
  }

  /**
   * Load from a specific Typesafe Config instance (useful for testing).
   */
  def loadFromConfig(config: TypesafeConfig): Result[LLM4SConfig] = {
    val source = if (config.hasPath("llm4s")) {
      ConfigSource.fromConfig(config.getConfig("llm4s"))
    } else {
      // Fallback to root if llm4s namespace not present
      ConfigSource.fromConfig(config)
    }

    ConfigErrorConverter.toResult(source.load[LLM4SConfig])
  }

  /**
   * Load provider configuration for the active model.
   *
   * Parses LLM_MODEL (e.g., "openai/gpt-4o") and returns the appropriate
   * provider configuration with context window settings.
   */
  def loadProvider(): Result[ProviderConfig] =
    for {
      config <- load()
      model <- config.llm.model.toRight(
        LLM4SConfigReader.createMissingConfigError(ConfigKeys.LLM_MODEL)
      )
      provider <- parseAndLoadProvider(model, config)
    } yield provider

  /**
   * Load tracing configuration.
   */
  def loadTracing(): Result[TSSettings] =
    load().map { config =>
      val mode = TracingMode.fromString(config.tracing.mode)
      val langfuse = LFConfig(
        url = config.tracing.langfuse.url.getOrElse(DefaultConfig.DEFAULT_LANGFUSE_URL),
        publicKey = config.tracing.langfuse.publicKey,
        secretKey = config.tracing.langfuse.secretKey,
        env = config.tracing.langfuse.env.getOrElse(DefaultConfig.DEFAULT_LANGFUSE_ENV),
        release = config.tracing.langfuse.release.getOrElse(DefaultConfig.DEFAULT_LANGFUSE_RELEASE),
        version = config.tracing.langfuse.version.getOrElse(DefaultConfig.DEFAULT_LANGFUSE_VERSION)
      )
      TSSettings(mode, langfuse)
    }

  /**
   * Load embeddings configuration.
   *
   * @return Tuple of (provider name, provider config)
   */
  def loadEmbeddings(): Result[(String, EmbeddingProviderConfig)] =
    load().flatMap { config =>
      val provider = config.embeddings.provider.getOrElse("openai").toLowerCase

      provider match {
        case "openai" =>
          val baseUrl = config.embeddings.openai.baseUrl
            .getOrElse("https://api.openai.com/v1")
          val model = config.embeddings.openai.model
            .getOrElse("text-embedding-ada-002")
          val apiKey = config.openai.apiKey

          apiKey match {
            case Some(key) =>
              Right(provider -> EmbeddingProviderConfig(baseUrl, model, key))
            case None =>
              Left(LLM4SConfigReader.createMissingConfigError(ConfigKeys.OPENAI_API_KEY))
          }

        case "voyage" =>
          val baseUrl = config.embeddings.voyage.baseUrl
            .getOrElse("https://api.voyageai.com/v1")
          val model = config.embeddings.voyage.model
            .getOrElse("voyage-2")
          val apiKey = config.embeddings.voyage.apiKey

          apiKey match {
            case Some(key) =>
              Right(provider -> EmbeddingProviderConfig(baseUrl, model, key))
            case None =>
              Left(LLM4SConfigReader.createMissingConfigError(ConfigKeys.VOYAGE_API_KEY))
          }

        case other =>
          Left(ConfigurationError(s"Unknown embedding provider: $other"))
      }
    }

  /**
   * Parse model spec and load the appropriate provider configuration.
   */
  private def parseAndLoadProvider(modelSpec: String, config: LLM4SConfig): Result[ProviderConfig] = {
    val normalized = Option(modelSpec).map(_.trim).getOrElse("")
    if (normalized.isEmpty) {
      Left(ConfigurationError(s"Missing model spec: set ${ConfigKeys.LLM_MODEL}"))
    } else {
      val parts = normalized.split("/", 2)
      val (prefix, modelName) =
        if (parts.length == 2) (parts(0).toLowerCase, parts(1))
        else (inferProviderFromConfig(config), parts(0))

      prefix match {
        case "openai"     => loadOpenAIConfig(modelName, config)
        case "openrouter" => loadOpenAIConfig(modelName, config)
        case "azure"      => loadAzureConfig(modelName, config)
        case "anthropic"  => loadAnthropicConfig(modelName, config)
        case "ollama"     => loadOllamaConfig(modelName, config)
        case other if other.nonEmpty =>
          Left(ConfigurationError(s"Unknown provider prefix: $other in '$modelSpec'"))
        case _ =>
          Left(ConfigurationError(s"Unable to infer provider for model '$modelSpec'"))
      }
    }
  }

  private def inferProviderFromConfig(config: LLM4SConfig): String = {
    val base = config.openai.baseUrl
    if (base.contains("openrouter.ai")) "openrouter" else "openai"
  }

  private def loadOpenAIConfig(modelName: String, config: LLM4SConfig): Result[OpenAIConfig] = {
    val (cw, rc) = getOpenAIContextWindow(modelName)
    config.openai.apiKey match {
      case Some(apiKey) =>
        Right(
          OpenAIConfig(
            apiKey = apiKey,
            model = modelName,
            organization = config.openai.organization,
            baseUrl = config.openai.baseUrl,
            contextWindow = cw,
            reserveCompletion = rc
          )
        )
      case None =>
        Left(LLM4SConfigReader.createMissingConfigError(ConfigKeys.OPENAI_API_KEY))
    }
  }

  private def loadAzureConfig(modelName: String, config: LLM4SConfig): Result[AzureConfig] = {
    val (cw, rc) = getAzureContextWindow(modelName)
    for {
      endpoint <- config.azure.endpoint.toRight(
        LLM4SConfigReader.createMissingConfigError(ConfigKeys.AZURE_API_BASE)
      )
      apiKey <- config.azure.apiKey.toRight(
        LLM4SConfigReader.createMissingConfigError(ConfigKeys.AZURE_API_KEY)
      )
    } yield AzureConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      model = modelName,
      apiVersion = config.azure.apiVersion.getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW),
      contextWindow = cw,
      reserveCompletion = rc
    )
  }

  private def loadAnthropicConfig(modelName: String, config: LLM4SConfig): Result[AnthropicConfig] = {
    val (cw, rc) = getAnthropicContextWindow(modelName)
    config.anthropic.apiKey match {
      case Some(apiKey) =>
        Right(
          AnthropicConfig(
            apiKey = apiKey,
            model = modelName,
            baseUrl = config.anthropic.baseUrl,
            contextWindow = cw,
            reserveCompletion = rc
          )
        )
      case None =>
        Left(LLM4SConfigReader.createMissingConfigError(ConfigKeys.ANTHROPIC_API_KEY))
    }
  }

  private def loadOllamaConfig(modelName: String, config: LLM4SConfig): Result[OllamaConfig] = {
    val (cw, rc) = getOllamaContextWindow(modelName)
    config.ollama.baseUrl match {
      case Some(baseUrl) =>
        Right(
          OllamaConfig(
            model = modelName,
            baseUrl = baseUrl,
            contextWindow = cw,
            reserveCompletion = rc
          )
        )
      case None =>
        Left(LLM4SConfigReader.createMissingConfigError(ConfigKeys.OLLAMA_BASE_URL))
    }
  }

  // Context window calculations (copied from original ProviderConfig implementations)
  private val standardReserve = 4096

  private def getOpenAIContextWindow(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  private def getAzureContextWindow(modelName: String): (Int, Int) =
    getOpenAIContextWindow(modelName) // Azure mirrors OpenAI models

  private def getAnthropicContextWindow(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("claude-3")       => (200000, standardReserve)
      case name if name.contains("claude-3.5")     => (200000, standardReserve)
      case name if name.contains("claude-instant") => (100000, standardReserve)
      case _                                       => (200000, standardReserve)
    }

  private def getOllamaContextWindow(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("llama2")    => (4096, standardReserve)
      case name if name.contains("llama3")    => (8192, standardReserve)
      case name if name.contains("codellama") => (16384, standardReserve)
      case name if name.contains("mistral")   => (32768, standardReserve)
      case _                                  => (8192, standardReserve)
    }
}
