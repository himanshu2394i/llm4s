package org.llm4s.samples.util

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
   * Validates that required environment variables are set for the specified LLM provider.
   *
   * Checks both LLM_MODEL and provider-specific API keys, returning helpful error messages
   * with setup instructions when configuration is missing or incomplete.
   *
   * Note: Uses direct environment access (suppressing scalafix) to provide early validation
   * before ConfigReader is invoked, giving better error messages to new users.
   *
   * @return Right(()) if environment is valid, Left(ConfigurationError) with helpful guidance if not
   */
  // scalafix:off DisableSyntax.NoSysEnv
  def validateEnvironment(): Result[Unit] = {
    val llmModel = sys.env.get("LLM_MODEL")

    llmModel match {
      case None =>
        Left(
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

      case Some(model) =>
        // Provider-specific validation and guidance
        val provider = model.split("/").headOption.getOrElse("")
        provider match {
          case "openai" =>
            sys.env.get("OPENAI_API_KEY") match {
              case None =>
                Left(
                  ConfigurationError(
                    s"""❌ Missing required environment variable: OPENAI_API_KEY
                       |
                       |For OpenAI, you need an API key from https://platform.openai.com/api-keys
                       |
                       |Set it with:
                       |  export OPENAI_API_KEY=sk-...
                       |
                       |Your current model: $model
                       |""".stripMargin
                  )
                )
              case Some(_) => Right(())
            }

          case "anthropic" =>
            sys.env.get("ANTHROPIC_API_KEY") match {
              case None =>
                Left(
                  ConfigurationError(
                    s"""❌ Missing required environment variable: ANTHROPIC_API_KEY
                       |
                       |For Anthropic, you need an API key from https://console.anthropic.com/
                       |
                       |Set it with:
                       |  export ANTHROPIC_API_KEY=sk-ant-...
                       |
                       |Your current model: $model
                       |""".stripMargin
                  )
                )
              case Some(_) => Right(())
            }

          case "azure" =>
            val missingVars = List(
              "AZURE_OPENAI_API_KEY"  -> sys.env.get("AZURE_OPENAI_API_KEY"),
              "AZURE_OPENAI_ENDPOINT" -> sys.env.get("AZURE_OPENAI_ENDPOINT")
            ).collect { case (key, None) => key }

            if (missingVars.nonEmpty) {
              Left(
                ConfigurationError(
                  s"""❌ Missing required environment variables: ${missingVars.mkString(", ")}
                     |
                     |For Azure OpenAI, you need:
                     |  export AZURE_OPENAI_API_KEY=...
                     |  export AZURE_OPENAI_ENDPOINT=https://<resource>.openai.azure.com/
                     |
                     |Your current model: $model""".stripMargin
                )
              )
            } else Right(())

          case "ollama" =>
            Right(()) // Ollama doesn't require API key (local server)

          case _ =>
            Right(()) // Unknown provider, let it fail later with context from library
        }
    }
  }
  // scalafix:on DisableSyntax.NoSysEnv
}
