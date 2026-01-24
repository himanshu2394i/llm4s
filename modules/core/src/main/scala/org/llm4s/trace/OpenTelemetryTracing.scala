package org.llm4s.trace


import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.config.OpenTelemetryConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.types.Result



class OpenTelemetryTracing(
  serviceName: String,
  endpoint: String,
  headers: Map[String, String]
) extends Tracing {

  private val tracer: Tracer = initializeTracer()

  private def initializeTracer(): Tracer = {
    val resource = Resource.getDefault.toBuilder
      .put(AttributeKey.stringKey("service.name"), serviceName)
      .build()

    val spanExporterBuilder = OtlpGrpcSpanExporter.builder()
      .setEndpoint(endpoint)

    headers.foreach { case (k, v) =>
      spanExporterBuilder.addHeader(k, v)
    }

    val spanProcessor = BatchSpanProcessor.builder(spanExporterBuilder.build()).build()

    val tracerProvider = SdkTracerProvider.builder()
      .setResource(resource)
      .addSpanProcessor(spanProcessor)
      .build()

    val openTelemetry = OpenTelemetrySdk.builder()
      .setTracerProvider(tracerProvider)
      .buildAndRegisterGlobal()

    openTelemetry.getTracer("org.llm4s")
  }

  override def traceEvent(event: TraceEvent): Result[Unit] = {
    val (spanName, attributes) = event match {
      case e: TraceEvent.AgentInitialized =>
        ("LLM4S Agent Run", Attributes.builder()
          .put("event.type", "trace-create")
          .put("input", e.query)
          .put("tools", e.tools.mkString(", "))
          .build())

      case e: TraceEvent.CompletionReceived =>
        ("LLM Completion", Attributes.builder()
          .put("event.type", "generation-create")
          .put("model", e.model)
          .put("completion_id", e.id)
          .put("tool_calls", e.toolCalls.toLong)
          .put("content", e.content)
          .build())

      case e: TraceEvent.ToolExecuted =>
        (s"Tool Execution: ${e.name}", Attributes.builder()
          .put("event.type", "span-create")
          .put("tool.name", e.name)
          .put("tool.input", e.input)
          .put("tool.output", e.output)
          .put("duration_ms", e.duration)
          .put("success", e.success)
          .build())

      case e: TraceEvent.ErrorOccurred =>
        ("Error", Attributes.builder()
          .put("event.type", "event-create")
          .put("error.message", e.error.getMessage)
          .put("error.type", e.error.getClass.getSimpleName)
          .put("context", e.context)
          .build())

      case e: TraceEvent.TokenUsageRecorded =>
        (s"Token Usage - ${e.operation}", Attributes.builder()
          .put("event.type", "event-create")
          .put("model", e.model)
          .put("operation", e.operation)
          .put("tokens.prompt", e.usage.promptTokens.toLong)
          .put("tokens.completion", e.usage.completionTokens.toLong)
          .put("tokens.total", e.usage.totalTokens.toLong)
          .build())

      case e: TraceEvent.CustomEvent =>
        (e.name, Attributes.builder()
          .put("event.type", "custom")
          .put("data", e.data.toString())
          .build())
      
      case e: TraceEvent.AgentStateUpdated =>
        ("Agent State Updated", Attributes.builder()
          .put("event.type", "state-update")
          .put("status", e.status)
          .put("message_count", e.messageCount.toLong)
          .put("log_count", e.logCount.toLong)
          .build())
          
      case e: TraceEvent.EmbeddingUsageRecorded =>
        (s"Embedding Usage - ${e.operation}", Attributes.builder()
          .put("event.type", "embedding-usage")
          .put("model", e.model)
          .put("operation", e.operation)
          .put("input_count", e.inputCount.toLong)
          .put("tokens.prompt", e.usage.promptTokens.toLong)
          .put("tokens.total", e.usage.totalTokens.toLong)
          .build())

      case e: TraceEvent.CostRecorded =>
        (s"Cost - ${e.operation}", Attributes.builder()
          .put("event.type", "cost")
          .put("model", e.model)
          .put("operation", e.operation)
          .put("token_count", e.tokenCount.toLong)
          .put("cost.usd", e.costUsd)
          .put("cost.type", e.costType)
          .build())

      case e: TraceEvent.RAGOperationCompleted =>
        (s"RAG ${e.operation}", Attributes.builder()
           .put("event.type", "rag-operation")
           .put("operation", e.operation)
           .put("duration_ms", e.durationMs)
           .put("embedding_tokens", e.embeddingTokens.map(_.toLong).getOrElse(0L))
           .put("llm_prompt_tokens", e.llmPromptTokens.map(_.toLong).getOrElse(0L))
           .put("llm_completion_tokens", e.llmCompletionTokens.map(_.toLong).getOrElse(0L))
           .put("total_cost_usd", e.totalCostUsd.getOrElse(0.0))
           .build())
    }

    val span = tracer.spanBuilder(spanName)
      .setSpanKind(SpanKind.CLIENT)
      .setAllAttributes(attributes)
      .startSpan()

    try {
      if (spanName == "Error") {
        event match {
          case e: TraceEvent.ErrorOccurred => span.recordException(e.error)
          case _ =>
        }
      }
    } finally {
      span.end()
    }
    
    Right(())
  }

  override def traceAgentState(state: AgentState): Result[Unit] = {
    val span = tracer.spanBuilder("Agent State Snapshot")
      .setAttribute("status", state.status.toString)
      .setAttribute("message_count", state.conversation.messages.length.toLong)
      .setAttribute("log_count", state.logs.length.toLong)
      .startSpan()

    span.end()
    Right(())
  }

  override def traceToolCall(toolName: String, input: String, output: String): Result[Unit] = {
    val event = TraceEvent.ToolExecuted(toolName, input, output, 0, true)
    traceEvent(event)
  }

  override def traceError(error: Throwable, context: String): Result[Unit] = {
    val event = TraceEvent.ErrorOccurred(error, context)
    traceEvent(event)
  }

  override def traceCompletion(completion: Completion, model: String): Result[Unit] = {
    val event = TraceEvent.CompletionReceived(
      id = completion.id,
      model = model,
      toolCalls = completion.message.toolCalls.size,
      content = completion.message.content
    )
    traceEvent(event)
  }

  override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = {
    val event = TraceEvent.TokenUsageRecorded(usage, model, operation)
    traceEvent(event)
  }
}

object OpenTelemetryTracing {
  def from(config: OpenTelemetryConfig): OpenTelemetryTracing = {
    new OpenTelemetryTracing(
      serviceName = config.serviceName,
      endpoint = config.endpoint,
      headers = config.headers
    )
  }
}
