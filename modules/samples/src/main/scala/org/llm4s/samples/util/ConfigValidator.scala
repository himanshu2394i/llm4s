package org.llm4s.samples.util

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

/**
 * Utility for validating environment configuration in examples.
 *
 * Provides early validation with helpful error messages to guide new users
 * through proper environment setup for different LLM providers.
 */
object ConfigValidator {

  /**
   * Represents a known LLM provider with type-safe enumeration.
   */
  sealed private trait Provider
  private object Provider {
    case object OpenAI               extends Provider
    case object Anthropic            extends Provider
    case object Azure                extends Provider
    case object Ollama               extends Provider
    case class Unknown(name: String) extends Provider

    def fromString(s: String): Provider = s.toLowerCase match {
      case "openai"    => OpenAI
      case "anthropic" => Anthropic
      case "azure"     => Azure
      case "ollama"    => Ollama
      case other       => Unknown(other)
    }
  }

  /**
   * Validates that required environment variables are set for the specified LLM provider.
   *
   * Checks both LLM_MODEL and provider-specific API keys, returning helpful error messages
   * with setup instructions when configuration is missing or incomplete.
   *
   * Uses the standard ConfigReader so both environment variables and application.conf values
   * are respected, matching how the core library resolves configuration.
   *
   * @return Right(()) if configuration is valid, Left(ConfigurationError) with helpful guidance if not
   */
  def validateEnvironment(): Result[Unit] =
    ConfigReader
      .LLMConfig()
      .flatMap { config =>
        for {
          model    <- checkLLMModel(config.get(ConfigKeys.LLM_MODEL))
          provider <- parseProvider(model)
          _        <- checkProviderConfiguration(provider, model, config)
        } yield ()
      }

  /**
   * Validates that LLM_MODEL environment variable is set.
   *
   * @param maybeModel optional LLM_MODEL value from environment
   * @return Right(model) if present, Left(ConfigurationError) with setup instructions if missing
   */
  private def checkLLMModel(maybeModel: Option[String]): Result[String] =
    maybeModel
      .filter(_.trim.nonEmpty)
      .toRight(
        ConfigurationError(
          """❌ Missing required environment variable: LLM_MODEL
          |
          |The LLM_MODEL variable specifies which LLM provider and model to use.
          |
          |Quick Start Examples:
          |  export LLM_MODEL=openai/gpt-4o
          |  export LLM_MODEL=anthropic/claude-3-5-sonnet-latest
          |  export LLM_MODEL=azure/<your-deployment-name>
          |  export LLM_MODEL=ollama/llama2
          |
          |After setting LLM_MODEL, you'll also need the API key for your provider.
          |Run this example again to see provider-specific instructions.
          |""".stripMargin
        )
      )

  /**
   * Parses the provider name from a model string (format: "provider/model-name").
   *
   * @param model the full model string
   * @return Right(Provider) if parsing succeeds, Left(ConfigurationError) if format is invalid
   */
  private def parseProvider(model: String): Result[Provider] = {
    val providerName = model.split("/").headOption.getOrElse("")
    if (providerName.isEmpty)
      Left(ConfigurationError(s"Invalid model format: $model. Expected format: provider/model-name"))
    else
      Right(Provider.fromString(providerName))
  }

  /**
   * Validates provider-specific configuration requirements.
   *
   * @param provider the LLM provider to validate
   * @param model the full model string for error messages
   * @return Right(()) if configuration is valid, Left(ConfigurationError) if required variables are missing
   */
  private def checkProviderConfiguration(provider: Provider, model: String, config: ConfigReader): Result[Unit] =
    provider match {
      case Provider.OpenAI =>
        checkSetting(
          config,
          ConfigKeys.OPENAI_API_KEY,
          model,
          "OpenAI",
          "https://platform.openai.com/api-keys",
          "sk-..."
        )

      case Provider.Anthropic =>
        checkSetting(
          config,
          ConfigKeys.ANTHROPIC_API_KEY,
          model,
          "Anthropic",
          "https://console.anthropic.com/",
          "sk-ant-..."
        )

      case Provider.Azure =>
        val missingVars = List(
          ConfigKeys.AZURE_API_KEY,
          ConfigKeys.AZURE_API_BASE
        ).filter(key => config.get(key).isEmpty)

        if (missingVars.nonEmpty) {
          Left(
            ConfigurationError(
              s"""❌ Missing required configuration values: ${missingVars.mkString(", ")}
                 |
                 |For Azure OpenAI, you need:
                 |  export AZURE_API_KEY=...
                 |  export AZURE_API_BASE=https://<resource>.openai.azure.com/
                 |
                 |Alternatively set them in application.conf:
                 |  llm4s.azure.apiKey  = "..."
                 |  llm4s.azure.endpoint = "https://<resource>.openai.azure.com/"
                 |
                 |Your current model: $model""".stripMargin
            )
          )
        } else Right(())

      case Provider.Ollama =>
        Right(()) // Ollama doesn't require API key (local server)

      case Provider.Unknown(_) =>
        Right(()) // Unknown provider, let it fail later with context from library
    }

  /**
   * Validates that a single environment variable is set for a provider.
   *
   * @param envVar the environment variable name to check
   * @param model the model string for error messages
   * @param providerName the human-readable provider name
   * @param signupUrl the URL where users can get API keys
   * @param examplePrefix example prefix for the API key value
   * @return Right(()) if variable is set, Left(ConfigurationError) with instructions if missing
   */
  private def checkSetting(
    config: ConfigReader,
    key: String,
    model: String,
    providerName: String,
    signupUrl: String,
    examplePrefix: String
  ): Result[Unit] =
    config
      .get(key)
      .map(_ => Right(()))
      .getOrElse(
        Left(
          ConfigurationError(
            s"""❌ Missing required configuration value: $key
             |
             |For $providerName, you need an API key from $signupUrl
             |
             |Set it with:
             |  export $key=$examplePrefix
             |Or in application.conf:
             |  llm4s.${providerName.toLowerCase}.apiKey = "$examplePrefix"
             |
             |Your current model: $model
             |""".stripMargin
          )
        )
      )
}
