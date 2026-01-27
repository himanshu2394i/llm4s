package org.llm4s.vertex

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{Conversation, UserMessage, CompletionOptions}

class VertexIntegrationSpec extends AnyFlatSpec with Matchers {
  
  val projectId = sys.env.get("VERTEX_PROJECT_ID")
  val location = sys.env.getOrElse("VERTEX_LOCATION", "us-central1")
  val shouldRun = projectId.isDefined

  "VertexClient" should "authenticate and complete a request" in {
    if (!shouldRun) {
      cancel("VERTEX_PROJECT_ID env var not set, skipping integration test")
    } else {
      val pid = projectId.get
      val loc = location
      // Vertex OpenAI-compatible endpoint
      val endpoint = s"https://$loc-aiplatform.googleapis.com/v1beta1/projects/$pid/locations/$loc/endpoints/openapi"
      
      val auth = new VertexAIAuth(pid, loc)
      val config = OpenAIConfig(
        apiKey = "dummy", // Not used with TokenCredential
        model = "google/gemini-1.5-flash-001",
        organization = None,
        baseUrl = endpoint,
        contextWindow = 128000,
        reserveCompletion = 4096
      )
      
      val clientResult = VertexClient(auth, config)
      clientResult should be (a [Right[_,_]])
      val client = clientResult.toOption.get
      
      val conversation = Conversation(Seq(UserMessage("Hello, are you Gemini?")))
      val response = client.complete(conversation, CompletionOptions())
      
      response match {
        case Right(completion) =>
          println(s"Vertex Response: ${completion.content}")
          completion.content should not be empty
          
        case Left(error) =>
          fail(s"Vertex call failed: $error")
      }
    }
  }
}
