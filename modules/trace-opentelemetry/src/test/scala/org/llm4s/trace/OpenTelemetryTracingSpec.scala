package org.llm4s.trace

import org.llm4s.llmconnect.config.OpenTelemetryConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenTelemetryTracingSpec extends AnyFlatSpec with Matchers {

  "TracingMode" should "parse opentelemetry strings correctly" in {
    TracingMode.fromString("opentelemetry") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("otel") shouldBe TracingMode.OpenTelemetry
    TracingMode.fromString("OpenTelemetry") shouldBe TracingMode.OpenTelemetry
  }

  it should "initialize without errors" in {
    val config = OpenTelemetryConfig(
      serviceName = "test-service",
      endpoint = "http://localhost:4317",
      headers = Map.empty
    )

    val tracing = OpenTelemetryTracing.from(config)
    tracing should not be null

    // We expect Right(()) because OpenTelemetry SDK handles connection failures asynchronously
    // or we successfully returned Right(()) even if the backend is down.
    // Init itself succeeds locally (it just builds the SDK).
    val result = tracing.traceEvent(TraceEvent.CustomEvent("TestEvent", ujson.Obj()))
    result shouldBe Right(())

    tracing.shutdown()
  }
}
