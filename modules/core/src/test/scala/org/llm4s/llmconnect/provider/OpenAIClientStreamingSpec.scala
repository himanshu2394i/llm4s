package org.llm4s.llmconnect.provider

import com.azure.ai.openai.models.{ ChatCompletions, ChatCompletionsOptions }
import com.azure.core.util.IterableStream
import com.azure.json.JsonProviders
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._
import scala.util.Using

final class OpenAIClientStreamingSpec extends AnyFlatSpec with Matchers {

  private def completionsFromJson(json: String): ChatCompletions =
    Using.resource(JsonProviders.createReader(json))(ChatCompletions.fromJson)

  "OpenAIClient.streamComplete" should "safely handle null/empty choices and update tokens only when finished" in {
    val model = "gpt-4"

    val config = OpenAIConfig.fromValues(
      modelName = model,
      apiKey = "test-api-key",
      organization = None,
      baseUrl = "https://example.invalid/v1"
    )

    val noChoices    = completionsFromJson("""{"id":"chatcmpl-1","created":0,"choices":null}""")
    val emptyChoices = completionsFromJson("""{"id":"chatcmpl-1","created":0,"choices":[]}""")
    val contentChunk = completionsFromJson(
      """{
        |"id":"chatcmpl-1",
        |"created":0,
        |"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]
        |}""".stripMargin
    )
    val stopChunkWithUsage = completionsFromJson(
      """{
        |"id":"chatcmpl-1",
        |"created":0,
        |"choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant"}}],
        |"usage":{"completion_tokens":5,"prompt_tokens":10,"total_tokens":15}
        |}""".stripMargin
    )

    val stream =
      new IterableStream[ChatCompletions](List(noChoices, emptyChoices, contentChunk, stopChunkWithUsage).asJava)

    val transport = new OpenAIClientTransport {
      override def getChatCompletions(model: String, options: ChatCompletionsOptions): ChatCompletions =
        throw new UnsupportedOperationException("not used in this test")

      override def getChatCompletionsStream(
        model: String,
        options: ChatCompletionsOptions
      ): IterableStream[ChatCompletions] =
        stream
    }

    val client = OpenAIClient.forTest(model, transport, config)

    val conversation = Conversation(Seq(UserMessage("hello")))
    val chunks       = scala.collection.mutable.ListBuffer.empty[String]

    val result = client.streamComplete(conversation, CompletionOptions(), c => chunks += c.content.getOrElse(""))

    result.isRight shouldBe true

    val completion = result.toOption.get
    completion.id shouldBe "chatcmpl-1"
    completion.model shouldBe model
    completion.content shouldBe "Hi"
    completion.usage.map(_.promptTokens) shouldBe Some(10)
    completion.usage.map(_.completionTokens) shouldBe Some(5)

    // Only the real content chunk contributes non-empty content.
    chunks.toList should contain("Hi")
  }
}
