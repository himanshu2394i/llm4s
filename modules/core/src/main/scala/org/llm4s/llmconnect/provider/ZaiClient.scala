package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.error.ThrowableOps._

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration
import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import scala.util.Try

class ZaiClient(config: ZaiConfig) extends LLMClient {
  private val httpClient = HttpClient.newHttpClient()
  private val logger     = org.slf4j.LoggerFactory.getLogger(getClass)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    val requestBody = createRequestBody(conversation, options)

    // Log the request for debugging
    logger.debug(s"Sending request to Z.ai API at ${config.baseUrl}/chat/completions")
    logger.debug(s"Request body: ${requestBody.render()}")

    // Also print to console for immediate debugging
    println("\n🔍 Z.ai API Request Debug:")
    println(s"URL: ${config.baseUrl}/chat/completions")
    println(s"Request body: ${requestBody.render()}")
    println()

    val attempt =
      Try {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", s"Bearer ${config.apiKey}")
          .header("User-Agent", "llm4s-coding-assistant/1.0")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
          .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Log response details for debugging
        logger.debug(s"Response status: ${response.statusCode()}")
        logger.debug(s"Response body: ${response.body()}")

        // Also print response to console
        println(s"🔍 Z.ai API Response Debug:")
        println(s"Status: ${response.statusCode()}")
        println(s"Body: ${response.body()}")
        println()

        response
      }.toEither.left
        .map(_.toLLMError)

    attempt.flatMap { response =>
      response.statusCode() match {
        case 200 =>
          val responseJson = ujson.read(response.body())
          Right(parseCompletion(responseJson))
        case 401    => Left(AuthenticationError("zai", "Invalid API key"))
        case 429    => Left(RateLimitError("zai"))
        case status => Left(ServiceError(status, "zai", s"Z.ai API error: ${response.body()}"))
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val requestBody = createRequestBody(conversation, options)
    requestBody("stream") = true

    val accumulator = StreamingAccumulator.create()

    val attempt =
      Try {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", s"Bearer ${config.apiKey}")
          .header("User-Agent", "llm4s-coding-assistant/1.0")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
          .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != 200) {
          val errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
          response.statusCode() match {
            case 401 => throw new RuntimeException(AuthenticationError("zai", "Invalid API key").formatted)
            case 429 => throw new RuntimeException(RateLimitError("zai").formatted)
            case status =>
              throw new RuntimeException(
                s"${ServiceError(status, "zai", s"Z.ai API error: $errorBody").formatted}"
              )
          }
        }

        val sseParser = SSEParser.createStreamingParser()
        val reader    = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
        val loopTry = Try {
          var line: String = null
          while ({ line = reader.readLine(); line != null }) {
            sseParser.addChunk(line + "\n")
            while (sseParser.hasEvents)
              sseParser.nextEvent().foreach { event =>
                event.data.foreach { data =>
                  if (data != "[DONE]") {
                    val json   = ujson.read(data)
                    val chunks = parseStreamingChunks(json)
                    chunks.foreach { c =>
                      accumulator.addChunk(c)
                      onChunk(c)
                    }
                  }
                }
              }
          }
        }
        Try(reader.close()); Try(response.body().close())
        loopTry.get
      }.toEither.left
        .map(_.toLLMError)

    attempt.flatMap(_ => accumulator.toCompletion)
  }

  private def parseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] = {
    val choices = json("choices").arr
    if (choices.nonEmpty) {
      val choice = choices(0)
      val delta  = choice("delta")

      // Extract content from array format if needed
      val content = delta.obj.get("content").flatMap { content =>
        content.strOpt.orElse {
          // Handle array format: [{"type": "text", "text": "..."}]
          content.arrOpt.flatMap(arr => arr.headOption.flatMap(obj => obj.obj.get("text").flatMap(_.strOpt)))
        }
      }

      val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")

      val toolCalls = delta.obj.get("tool_calls").map(_.arr).getOrElse(Seq.empty).collect {
        case call if call.obj.contains("function") =>
          val function = call("function")
          val rawArgs  = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("")
          ToolCall(
            id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
            name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
            arguments = parseStreamingArguments(rawArgs)
          )
      }

      val chunkId = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
      if (toolCalls.isEmpty) {
        Seq(
          StreamedChunk(
            id = chunkId,
            content = content,
            toolCall = None,
            finishReason = finishReason,
            thinkingDelta = None
          )
        )
      } else {
        val first = StreamedChunk(
          id = chunkId,
          content = content,
          toolCall = Some(toolCalls.head),
          finishReason = finishReason,
          thinkingDelta = None
        )
        val rest = toolCalls.drop(1).map { tc =>
          StreamedChunk(
            id = chunkId,
            content = None,
            toolCall = Some(tc),
            finishReason = None,
            thinkingDelta = None
          )
        }
        Seq(first) ++ rest
      }
    } else {
      Seq.empty
    }
  }

  private def parseStreamingArguments(raw: String): ujson.Value =
    if (raw.isEmpty) ujson.Null else scala.util.Try(ujson.read(raw)).getOrElse(ujson.Str(raw))

  private def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content))))
      case SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content))))
      case AssistantMessage(content, toolCalls) =>
        val base = ujson.Obj(
          "role"    -> "assistant",
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content.getOrElse(""))))
        )
        if (toolCalls.nonEmpty) {
          base("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"   -> tc.id,
              "type" -> "function",
              "function" -> ujson.Obj(
                "name"      -> tc.name,
                "arguments" -> tc.arguments.render()
              )
            )
          })
        }
        base
      case ToolMessage(toolCallId, content) =>
        ujson.Obj(
          "role"         -> "tool",
          "tool_call_id" -> toolCallId,
          "content"      -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> ujson.Str(content)))
        )
    }

    val base = ujson.Obj(
      "model"       -> config.model,
      "messages"    -> ujson.Arr.from(messages),
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )

    options.maxTokens.foreach(mt => base("max_tokens") = mt)
    if (options.presencePenalty != 0) base("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0) base("frequency_penalty") = options.frequencyPenalty

    if (options.tools.nonEmpty) {
      val toolRegistry = new ToolRegistry(options.tools)
      base("tools") = toolRegistry.getOpenAITools()
    }

    base
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val choice  = json("choices")(0)
    val message = choice("message")

    val toolCalls = Option(message.obj.get("tool_calls"))
      .map(tc => parseToolCalls(tc))
      .getOrElse(Seq.empty)

    // Extract content from array format if needed
    val contentStr = message.obj.get("content") match {
      case Some(content) =>
        content.strOpt.getOrElse {
          // Handle array format: [{"type": "text", "text": "..."}]
          content.arrOpt
            .flatMap(arr => arr.headOption.flatMap(obj => obj.obj.get("text").flatMap(_.strOpt)))
            .getOrElse("")
        }
      case None => ""
    }

    val usage = Option(json.obj.get("usage")).flatMap { u =>
      val usageObjOpt =
        u.objOpt.orElse(u.arrOpt.flatMap(_.headOption.flatMap(_.objOpt)))
      usageObjOpt.map { usageObj =>
        TokenUsage(
          promptTokens = usageObj.value("prompt_tokens").num.toInt,
          completionTokens = usageObj.value("completion_tokens").num.toInt,
          totalTokens = usageObj.value("total_tokens").num.toInt
        )
      }
    }

    Completion(
      id = json("id").str,
      created = json("created").num.toLong,
      content = contentStr,
      model = json("model").str,
      message = AssistantMessage(
        contentOpt = Some(contentStr),
        toolCalls = toolCalls.toList
      ),
      toolCalls = toolCalls.toList,
      usage = usage,
      thinking = None
    )
  }

  private def parseToolCalls(toolCallsJson: ujson.Value): Seq[ToolCall] =
    toolCallsJson.arr.map { call =>
      val function = call("function")
      ToolCall(
        id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
        name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
        arguments = function.obj.get("arguments").getOrElse(ujson.Str(""))
      )
    }.toSeq

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion
}

object ZaiClient {
  import org.llm4s.types.TryOps

  def apply(config: ZaiConfig): Result[ZaiClient] =
    Try(new ZaiClient(config)).toResult
}
