package org.llm4s.vertex

import com.azure.ai.openai.OpenAIClientBuilder
import com.azure.core.credential.{ AccessToken, TokenCredential, TokenRequestContext }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.types.Result
import org.llm4s.types.TryOps
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import scala.util.Try

object VertexClient {

  /**
   * Creates an OpenAIClient configured for Vertex AI.
   *
   * @param auth The VertexAIAuth instance for token management
   * @param config The OpenAIConfig (baseUrl should be the Vertex endpoint)
   */
  def apply(auth: VertexAIAuth, config: OpenAIConfig): Result[LLMClient] = {
    Try {
      val credential = new VertexTokenCredential(auth)
      
      val azureClient = new OpenAIClientBuilder()
        .credential(credential)
        .endpoint(config.baseUrl)
        .buildClient()

      new OpenAIClient(config.model, azureClient, config)
    }.toResult
  }

  private class VertexTokenCredential(auth: VertexAIAuth) extends TokenCredential {
    override def getToken(request: TokenRequestContext): Mono[AccessToken] = {
      Mono.fromCallable { () =>
        val token = auth.getAccessToken()
        // Vertex tokens are valid for 1 hour usually. 
        // We set expiration to now + 1 hour as a rough estimate.
        new AccessToken(token, OffsetDateTime.now().plusHours(1))
      }
    }
  }
}
