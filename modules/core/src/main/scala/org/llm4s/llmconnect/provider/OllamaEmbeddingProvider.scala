package org.llm4s.llmconnect.provider

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
import sttp.client4._
import ujson.{ Obj, read }

import scala.util.Try

/**
 * Embedding provider for Ollama local models.
 *
 * Ollama provides local embedding models that can run without API keys.
 * Popular models include:
 *   - nomic-embed-text (768 dimensions)
 *   - mxbai-embed-large (1024 dimensions)
 *   - all-minilm (384 dimensions)
 *
 * Usage:
 * {{{
 *   export EMBEDDING_PROVIDER=ollama
 *   export OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434
 *   export OLLAMA_EMBEDDING_MODEL=nomic-embed-text
 *   # No API key required for local Ollama
 * }}}
 */
class OllamaEmbeddingProvider(config: ConfigReader) extends EmbeddingProvider {

  private val backend = DefaultSyncBackend()
  private val logger  = LoggerFactory.getLogger(getClass)

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    val model = request.model.name
    val input = request.input

    // Lazily read provider config; surface missing envs as a clean EmbeddingError
    val cfgEither: Either[EmbeddingError, EmbeddingProviderConfig] =
      Try(EmbeddingConfig.ollama(config)).toEither.left.map { e =>
        EmbeddingError(
          code = Some("400"),
          message = s"Missing Ollama configuration: ${e.getMessage}",
          provider = "ollama"
        )
      }

    cfgEither.flatMap { cfg =>
      // Ollama requires embedding each text separately
      val results = input.map(text => embedSingle(cfg, model, text))

      // Collect results, fail fast on first error
      val (errors, vectors) = results.partition(_.isLeft)
      if (errors.nonEmpty) {
        errors.head
          .asInstanceOf[Left[EmbeddingError, Vector[Double]]]
          .swap
          .toOption
          .map(Left(_))
          .getOrElse(Left(EmbeddingError(code = Some("500"), message = "Unknown error", provider = "ollama")))
      } else {
        val embeddings = vectors.map(_.toOption.get)
        val metadata   = Map("provider" -> "ollama", "model" -> model, "count" -> input.size.toString)
        Right(EmbeddingResponse(embeddings = embeddings, metadata = metadata))
      }
    }
  }

  private def embedSingle(
    cfg: EmbeddingProviderConfig,
    model: String,
    text: String
  ): Either[EmbeddingError, Vector[Double]] = {
    // Ollama uses different API format: /api/embeddings with "prompt" field
    val payload = Obj(
      "model"  -> model,
      "prompt" -> text
    )

    val url = uri"${cfg.baseUrl}/api/embeddings"

    logger.debug(s"[OllamaEmbeddingProvider] POST $url model=$model text_length=${text.length}")

    val respEither: Either[EmbeddingError, Response[Either[String, String]]] =
      Try {
        val req = basicRequest
          .post(url)
          .header("Content-Type", "application/json")
          .body(payload.render())

        // Add API key header if provided (optional for Ollama)
        val reqWithAuth = if (cfg.apiKey.nonEmpty && cfg.apiKey != "not-required") {
          req.header("Authorization", s"Bearer ${cfg.apiKey}")
        } else {
          req
        }

        reqWithAuth.send(backend)
      }.toEither.left
        .map(e =>
          EmbeddingError(code = Some("502"), message = s"HTTP request failed: ${e.getMessage}", provider = "ollama")
        )

    respEither.flatMap { response =>
      response.body match {
        case Right(body) =>
          Try {
            val json   = read(body)
            val vector = json("embedding").arr.map(_.num).toVector
            vector
          }.toEither.left
            .map { ex =>
              logger.error(s"[OllamaEmbeddingProvider] Parse error: ${ex.getMessage}")
              EmbeddingError(code = Some("502"), message = s"Parsing error: ${ex.getMessage}", provider = "ollama")
            }

        case Left(errorMsg) =>
          logger.error(s"[OllamaEmbeddingProvider] HTTP error: $errorMsg")
          Left(
            EmbeddingError(
              code = Some("502"),
              message = errorMsg,
              provider = "ollama"
            )
          )
      }
    }
  }
}

object OllamaEmbeddingProvider {
  def apply(config: ConfigReader): OllamaEmbeddingProvider = new OllamaEmbeddingProvider(config)

  /** Typed-config factory that avoids ConfigReader at call sites. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val backend = DefaultSyncBackend()
    private val logger  = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
      val model = request.model.name
      val input = request.input

      // Ollama requires embedding each text separately
      val results = input.map(text => embedSingle(model, text))

      // Collect results, fail fast on first error
      val (errors, vectors) = results.partition(_.isLeft)
      if (errors.nonEmpty) {
        errors.head
          .asInstanceOf[Left[EmbeddingError, Vector[Double]]]
          .swap
          .toOption
          .map(Left(_))
          .getOrElse(Left(EmbeddingError(code = Some("500"), message = "Unknown error", provider = "ollama")))
      } else {
        val embeddings = vectors.map(_.toOption.get)
        val metadata   = Map("provider" -> "ollama", "model" -> model, "count" -> input.size.toString)
        Right(EmbeddingResponse(embeddings = embeddings, metadata = metadata))
      }
    }

    private def embedSingle(model: String, text: String): Either[EmbeddingError, Vector[Double]] = {
      val payload = Obj(
        "model"  -> model,
        "prompt" -> text
      )

      val url = uri"${cfg.baseUrl}/api/embeddings"
      logger.debug(s"[OllamaEmbeddingProvider] POST $url model=$model text_length=${text.length}")

      val respEither: Either[EmbeddingError, Response[Either[String, String]]] =
        Try {
          val req = basicRequest
            .post(url)
            .header("Content-Type", "application/json")
            .body(payload.render())

          // Add API key header if provided (optional for Ollama)
          val reqWithAuth = if (cfg.apiKey.nonEmpty && cfg.apiKey != "not-required") {
            req.header("Authorization", s"Bearer ${cfg.apiKey}")
          } else {
            req
          }

          reqWithAuth.send(backend)
        }.toEither.left
          .map(e =>
            EmbeddingError(code = Some("502"), message = s"HTTP request failed: ${e.getMessage}", provider = "ollama")
          )

      respEither.flatMap { response =>
        response.body match {
          case Right(body) =>
            Try {
              val json   = read(body)
              val vector = json("embedding").arr.map(_.num).toVector
              vector
            }.toEither.left
              .map { ex =>
                logger.error(s"[OllamaEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = Some("502"), message = s"Parsing error: ${ex.getMessage}", provider = "ollama")
              }
          case Left(errorMsg) =>
            logger.error(s"[OllamaEmbeddingProvider] HTTP error: $errorMsg")
            Left(EmbeddingError(code = Some("502"), message = errorMsg, provider = "ollama"))
        }
      }
    }
  }
}
