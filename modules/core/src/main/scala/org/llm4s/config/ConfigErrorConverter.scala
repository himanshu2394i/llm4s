package org.llm4s.config

import pureconfig.error._
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

/**
 * Converts PureConfig failures to LLM4S errors while preserving
 * the excellent context-aware error messages.
 *
 * This converter maps HOCON paths back to environment variable names
 * and provides provider-specific setup instructions.
 */
object ConfigErrorConverter {

  /**
   * Convert PureConfig ConfigReaderFailures to Result with helpful error messages.
   */
  def toResult[A](result: Either[ConfigReaderFailures, A]): Result[A] = result match {
    case Right(value)   => Right(value)
    case Left(failures) => Left(convertFailures(failures))
  }

  private def convertFailures(failures: ConfigReaderFailures): ConfigurationError = {
    val messages  = failures.toList.map(formatFailure)
    val helpTexts = failures.toList.flatMap(extractHelpText).distinct

    val combinedMessage = if (helpTexts.nonEmpty) {
      messages.mkString("; ") + helpTexts.mkString("")
    } else {
      messages.mkString("; ")
    }

    ConfigurationError(s"Configuration error: $combinedMessage")
  }

  private def formatFailure(failure: ConfigReaderFailure): String = failure match {
    case ConvertFailure(KeyNotFound(key, _), location, _) =>
      val path = location.map(_.description).getOrElse("root")
      s"Missing key '$key' at $path"

    case ConvertFailure(CannotConvert(value, toType, reason), location, _) =>
      val path = location.map(_.description).getOrElse("root")
      s"Cannot convert '$value' to $toType at $path: $reason"

    case ConvertFailure(EmptyStringFound(typ), location, _) =>
      val path = location.map(_.description).getOrElse("root")
      s"Empty string found for type $typ at $path"

    case ConvertFailure(WrongType(found, expected), location, _) =>
      val path = location.map(_.description).getOrElse("root")
      s"Wrong type at $path: expected $expected but found $found"

    case other =>
      other.description
  }

  /**
   * Extract context-aware help text based on the failure path.
   * Maps HOCON paths back to environment variable names and provides
   * provider-specific setup instructions.
   */
  private def extractHelpText(failure: ConfigReaderFailure): Option[String] = {
    val path = failure match {
      case ConvertFailure(KeyNotFound(key, _), location, _) =>
        Some(location.map(l => s"${l.description}.$key").getOrElse(key))
      case ConvertFailure(_, location, _) =>
        location.map(_.description)
      case _ =>
        None
    }

    path.flatMap(pathToHelpText)
  }

  /**
   * Maps HOCON config paths to helpful error messages with setup instructions.
   * Preserves the original context-aware help from ConfigReader.createMissingConfigError.
   */
  private def pathToHelpText(path: String): Option[String] = {
    // Normalize path for matching
    val normalizedPath = path.toLowerCase.replace("-", "")

    normalizedPath match {
      case p if p.contains("llm.model") || p.contains("llm") && p.endsWith("model") =>
        Some(
          """
            |
            |Set the LLM provider and model to use.
            |
            |Examples:
            |  export LLM_MODEL=openai/gpt-4o
            |  export LLM_MODEL=anthropic/claude-3-5-sonnet-latest
            |  export LLM_MODEL=azure/<your-deployment-name>
            |  export LLM_MODEL=ollama/llama2
            |
            |See: https://github.com/llm4s/llm4s#configuration""".stripMargin
        )

      case p if p.contains("openai") && p.contains("apikey") =>
        Some(
          """
            |
            |Get your OpenAI API key from: https://platform.openai.com/api-keys
            |Then set it with:
            |  export OPENAI_API_KEY=sk-...
            |
            |Or in application.conf:
            |  llm4s.openai.apiKey = "sk-..."
            |
            |See: https://github.com/llm4s/llm4s#openai-setup""".stripMargin
        )

      case p if p.contains("anthropic") && p.contains("apikey") =>
        Some(
          """
            |
            |Get your Anthropic API key from: https://console.anthropic.com/
            |Then set it with:
            |  export ANTHROPIC_API_KEY=sk-ant-...
            |
            |Or in application.conf:
            |  llm4s.anthropic.apiKey = "sk-ant-..."
            |
            |See: https://github.com/llm4s/llm4s#anthropic-setup""".stripMargin
        )

      case p if p.contains("azure") && p.contains("apikey") =>
        Some(
          """
            |
            |Set your Azure OpenAI API key:
            |  export AZURE_API_KEY=...
            |
            |You'll also need:
            |  export AZURE_API_BASE=https://<resource>.openai.azure.com/
            |
            |Optionally:
            |  export AZURE_API_VERSION=2025-01-01-preview
            |
            |See: https://github.com/llm4s/llm4s#azure-setup""".stripMargin
        )

      case p if p.contains("azure") && p.contains("endpoint") =>
        Some(
          """
            |
            |Set your Azure OpenAI endpoint:
            |  export AZURE_API_BASE=https://<resource>.openai.azure.com/
            |
            |You'll also need:
            |  export AZURE_API_KEY=...
            |
            |See: https://github.com/llm4s/llm4s#azure-setup""".stripMargin
        )

      case p if p.contains("langfuse") && (p.contains("publickey") || p.contains("secretkey")) =>
        Some(
          """
            |
            |Get your Langfuse keys from: https://cloud.langfuse.com/
            |Then set them with:
            |  export LANGFUSE_PUBLIC_KEY=pk-lf-...
            |  export LANGFUSE_SECRET_KEY=sk-lf-...
            |
            |See: https://github.com/llm4s/llm4s#tracing""".stripMargin
        )

      case p if p.contains("voyage") && p.contains("apikey") =>
        Some(
          """
            |
            |Get your Voyage AI API key from: https://www.voyageai.com/
            |Then set it with:
            |  export VOYAGE_API_KEY=...
            |
            |See: https://github.com/llm4s/llm4s#embeddings""".stripMargin
        )

      case p if p.contains("embeddings") && p.contains("provider") =>
        Some(
          """
            |
            |Set the embeddings provider to use:
            |  export EMBEDDING_PROVIDER=openai
            |  export EMBEDDING_PROVIDER=voyage
            |
            |See: https://github.com/llm4s/llm4s#embeddings""".stripMargin
        )

      case p if p.contains("ollama") && p.contains("baseurl") =>
        Some(
          """
            |
            |Set your Ollama server URL:
            |  export OLLAMA_BASE_URL=http://localhost:11434
            |
            |Make sure Ollama is running locally or specify your server address.
            |
            |See: https://github.com/llm4s/llm4s#ollama-setup""".stripMargin
        )

      case _ =>
        None
    }
  }
}
