package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{
  Conversation,
  CompletionOptions,
  ToolMessage,
  UserMessage,
  AssistantMessage,
  ToolCall
}
import org.llm4s.metrics.MetricsCollector

/**
 * Tests for DeepSeekClient ToolMessage encoding.
 *
 * These tests verify that:
 * - ToolMessage fields are encoded in the correct order (content, toolCallId)
 * - The request JSON has the correct structure for tool responses
 */
class DeepSeekClientToolMessageTest extends AnyFlatSpec with Matchers {

  private def createTestConfig: DeepSeekConfig = DeepSeekConfig.fromValues(
    modelName = "deepseek-chat",
    apiKey = "test-api-key-for-tool-message-testing",
    baseUrl = "https://example.invalid"
  )

  "DeepSeekClient" should "encode ToolMessage with correct field order" in {
    val client = DeepSeekClient(createTestConfig, MetricsCollector.noop).toOption.get

    // Create a conversation with a ToolMessage
    val conversation = Conversation(
      Seq(
        UserMessage("What's the weather?"),
        AssistantMessage(
          contentOpt = None,
          toolCalls = List(
            ToolCall(
              id = "call_abc123",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "San Francisco")
            )
          )
        ),
        ToolMessage(
          content = """{"temperature": 72, "condition": "sunny"}""",
          toolCallId = "call_abc123"
        )
      )
    )
   
    
   // Directly calls the extracted helper to verify request encoding.
   // This avoids using reflection on private client methods.
    val requestBody = DeepSeekClient.buildRequestBody(
      createTestConfig.model,
      conversation,
      CompletionOptions()
   )


    // Verify the messages array
    val messages = requestBody("messages").arr

  

    // The third message must be the tool response generated after the assistant's tool call
    val toolMessage = messages(2).obj

    // Verify the encoding is correct
    toolMessage("role").str shouldBe "tool"
    toolMessage("tool_call_id").str shouldBe "call_abc123"
    toolMessage("content").str shouldBe """{"temperature": 72, "condition": "sunny"}"""

    // Critical assertion: verify that content and toolCallId are NOT swapped
    // If they were swapped, tool_call_id would contain the JSON content
    (toolMessage("tool_call_id").str should not).include("temperature")
    (toolMessage("tool_call_id").str should not).include("sunny")
  }

  it should "handle multiple ToolMessages correctly" in {
    val client = DeepSeekClient(createTestConfig, MetricsCollector.noop).toOption.get

    val conversation = Conversation(
      Seq(
        UserMessage("Get weather for multiple cities"),
        AssistantMessage(
          contentOpt = None,
          toolCalls = List(
            ToolCall(
              id = "call_1",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "SF")
            ),
            ToolCall(
              id = "call_2",
              name = "get_weather",
              arguments = ujson.Obj("location" -> "NYC")
            )
          )
        ),
        ToolMessage(
          content = """{"temp": 72}""",
          toolCallId = "call_1"
        ),
        ToolMessage(
          content = """{"temp": 45}""",
          toolCallId = "call_2"
        )
      )
    )
     // Directly calls the extracted helper to verify request encoding.
     // This avoids using reflection on private client methods.
    val requestBody = DeepSeekClient.buildRequestBody(
      createTestConfig.model,
      conversation,
      CompletionOptions()
    )

    val messages = requestBody("messages").arr

    // Verify first ToolMessage
    val toolMsg1 = messages(2).obj
    toolMsg1("tool_call_id").str shouldBe "call_1"
    toolMsg1("content").str shouldBe """{"temp": 72}"""

    // Verify second ToolMessage
    val toolMsg2 = messages(3).obj
    toolMsg2("tool_call_id").str shouldBe "call_2"
    toolMsg2("content").str shouldBe """{"temp": 45}"""
  }
}



  