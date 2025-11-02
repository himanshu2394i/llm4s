package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.error.NotFoundError
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.config.ProviderConfigLoader
import org.llm4s.config.ConfigKeys.LLM_MODEL
import org.llm4s.llmconnect.config.{ EmbeddingConfig => EmbCfg }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.TracingMode
import org.llm4s.config.DefaultConfig

import scala.util.Try

trait ConfigReader {
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)

  /**
   * Requires a configuration value to be present, returning a helpful error if missing.
   *
   * Provides context-aware error messages with provider-specific setup instructions,
   * making it easier for users to identify and fix configuration issues.
   *
   * @param key the configuration key to require
   * @return Right(value) if present and non-empty, Left(NotFoundError) with helpful guidance if missing
   */
  def require(key: String): Result[String] =
    get(key).filter(_.trim.nonEmpty).toRight(ConfigReader.createMissingConfigError(key))

  def getPath(path: String): Option[String] = get(path)
}

object ConfigReader {

  /**
   * Creates a helpful NotFoundError with context-aware guidance for missing configuration keys.
   *
   * Provides actionable error messages with provider-specific setup instructions,
   * example values, and documentation links to help users quickly resolve configuration issues.
   *
   * @param key the configuration key that is missing
   * @return NotFoundError with helpful guidance
   */
  private[config] def createMissingConfigError(key: String): NotFoundError = {
    import ConfigKeys._

    val helpText = key match {
      case LLM_MODEL =>
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

      case OPENAI_API_KEY =>
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

      case ANTHROPIC_API_KEY =>
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

      case AZURE_API_KEY =>
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

      case AZURE_API_BASE =>
        """
          |
          |Set your Azure OpenAI endpoint:
          |  export AZURE_API_BASE=https://<resource>.openai.azure.com/
          |
          |You'll also need:
          |  export AZURE_API_KEY=...
          |
          |Optionally:
          |  export AZURE_API_VERSION=2025-01-01-preview
          |
          |See: https://github.com/llm4s/llm4s#azure-setup""".stripMargin

      case LANGFUSE_PUBLIC_KEY | LANGFUSE_SECRET_KEY =>
        """
          |
          |Get your Langfuse keys from: https://cloud.langfuse.com/
          |Then set them with:
          |  export LANGFUSE_PUBLIC_KEY=pk-lf-...
          |  export LANGFUSE_SECRET_KEY=sk-lf-...
          |
          |See: https://github.com/llm4s/llm4s#tracing""".stripMargin

      case VOYAGE_API_KEY =>
        """
          |
          |Get your Voyage AI API key from: https://www.voyageai.com/
          |Then set it with:
          |  export VOYAGE_API_KEY=...
          |
          |See: https://github.com/llm4s/llm4s#embeddings""".stripMargin

      case EMBEDDING_PROVIDER =>
        """
          |
          |Set the embeddings provider to use:
          |  export EMBEDDING_PROVIDER=openai
          |  export EMBEDDING_PROVIDER=voyage
          |
          |See: https://github.com/llm4s/llm4s#embeddings""".stripMargin

      case _ =>
        // Generic guidance for unknown keys
        s"""
           |
           |Set the environment variable:
           |  export $key=<value>
           |
           |Or in application.conf (check keyMapping for the path)
           |
           |See: https://github.com/llm4s/llm4s#configuration""".stripMargin
    }

    NotFoundError(s"Missing required configuration: $key$helpText", key)
  }

  def apply(map: Map[String, String]): ConfigReader = new ConfigReader {
    override def get(key: String): Option[String] = map.get(key)
  }

  // Flat key -> llm4s.* path mapping for unified config resolution
  private val keyMapping: Map[String, String] = {
    import ConfigKeys._
    Map(
      // Core
      LLM_MODEL -> "llm4s.llm.model",
      // OpenAI
      OPENAI_API_KEY  -> "llm4s.openai.apiKey",
      OPENAI_BASE_URL -> "llm4s.openai.baseUrl",
      OPENAI_ORG      -> "llm4s.openai.organization",
      // Azure OpenAI
      AZURE_API_BASE    -> "llm4s.azure.endpoint",
      AZURE_API_KEY     -> "llm4s.azure.apiKey",
      AZURE_API_VERSION -> "llm4s.azure.apiVersion",
      // Anthropic
      ANTHROPIC_API_KEY  -> "llm4s.anthropic.apiKey",
      ANTHROPIC_BASE_URL -> "llm4s.anthropic.baseUrl",
      // Ollama
      OLLAMA_BASE_URL -> "llm4s.ollama.baseUrl",
      // Tracing (Langfuse)
      LANGFUSE_URL        -> "llm4s.tracing.langfuse.url",
      LANGFUSE_PUBLIC_KEY -> "llm4s.tracing.langfuse.publicKey",
      LANGFUSE_SECRET_KEY -> "llm4s.tracing.langfuse.secretKey",
      LANGFUSE_ENV        -> "llm4s.tracing.langfuse.env",
      LANGFUSE_RELEASE    -> "llm4s.tracing.langfuse.release",
      LANGFUSE_VERSION    -> "llm4s.tracing.langfuse.version",
      // Embeddings (core)
      EMBEDDING_PROVIDER   -> "llm4s.embeddings.provider",
      EMBEDDING_INPUT_PATH -> "llm4s.embeddings.inputPath",
      EMBEDDING_QUERY      -> "llm4s.embeddings.query",
      // Embeddings (OpenAI)
      OPENAI_EMBEDDING_BASE_URL -> "llm4s.embeddings.openai.baseUrl",
      OPENAI_EMBEDDING_MODEL    -> "llm4s.embeddings.openai.model",
      // Embeddings (Voyage)
      VOYAGE_API_KEY            -> "llm4s.embeddings.voyage.apiKey",
      VOYAGE_EMBEDDING_BASE_URL -> "llm4s.embeddings.voyage.baseUrl",
      VOYAGE_EMBEDDING_MODEL    -> "llm4s.embeddings.voyage.model",
      // Embeddings (chunking)
      CHUNK_SIZE       -> "llm4s.embeddings.chunking.size",
      CHUNK_OVERLAP    -> "llm4s.embeddings.chunking.overlap",
      CHUNKING_ENABLED -> "llm4s.embeddings.chunking.enabled"
    )
  }

  // Use anonymous class for Scala 2.13 compatibility (no SAM for Scala traits)
  def LLMConfig(): Result[ConfigReader] =
    Try {
      // Ensure we see fresh system properties between test cases/runs
      // scalafix:off DisableSyntax.NoConfigFactory
      ConfigFactory.invalidateCaches()
      val conf = ConfigFactory.load() // honors -D, application.conf (if present), reference.conf
      // scalafix:on DisableSyntax.NoConfigFactory
      new ConfigReader {
        private def readString(path: String): Option[String] =
          if (conf.hasPath(path)) scala.util.Try(conf.getString(path)).toOption.filter(_.trim.nonEmpty) else None

        override def getPath(path: String): Option[String] = readString(path)

        override def get(key: String): Option[String] = {
          val mapped = keyMapping.get(key)
          // Priority: mapped llm4s.* path -> direct path (may include dots) -> legacy flat key
          mapped.flatMap(getPath).orElse(getPath(key)).orElse(readString(key))
        }
      }
    }.toResult

  object Provider {
    def apply(): Result[ProviderConfig] =
      LLMConfig().flatMap(cfg => cfg.require(LLM_MODEL).flatMap(model => ProviderConfigLoader(model, cfg)))
  }

  object Embeddings {

    /** Returns the active provider name and its validated config. */
    def apply(): Result[(String, EmbeddingProviderConfig)] =
      LLMConfig().flatMap { cfg =>
        scala.util.Try {
          val provider = EmbCfg.activeProvider(cfg).toLowerCase
          val conf = provider match {
            case "openai" => EmbCfg.openAI(cfg)
            case "voyage" => EmbCfg.voyage(cfg)
            case other    => throw new RuntimeException(s"Unknown embedding provider: $other")
          }
          EmbCfg.validateProviderConfig(conf) match {
            case Left(err) => throw new RuntimeException(err)
            case Right(ok) => provider -> ok
          }
        }.toResult
      }
  }

  object TracingConf {
    def apply(): Result[TracingSettings] =
      LLMConfig().map { cfg =>
        val modeStr   = cfg.getPath("llm4s.tracing.mode").orElse(cfg.get("TRACING_MODE")).getOrElse("console")
        val mode      = TracingMode.fromString(modeStr)
        val url       = cfg.get("LANGFUSE_URL").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_URL)
        val publicKey = cfg.get("LANGFUSE_PUBLIC_KEY").filter(_.nonEmpty)
        val secretKey = cfg.get("LANGFUSE_SECRET_KEY").filter(_.nonEmpty)
        val env       = cfg.get("LANGFUSE_ENV").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_ENV)
        val release   = cfg.get("LANGFUSE_RELEASE").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_RELEASE)
        val version   = cfg.get("LANGFUSE_VERSION").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_VERSION)
        val lfCfg     = LangfuseConfig(url, publicKey, secretKey, env, release, version)
        org.llm4s.llmconnect.config.TracingSettings(mode, lfCfg)
      }
  }
}
