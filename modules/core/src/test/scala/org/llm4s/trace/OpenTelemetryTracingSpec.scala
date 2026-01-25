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
    
    // Verify that traceEvent handles the event without throwing, 
    // even if the backend likely won't connect to anything.
    noException should be thrownBy {
      tracing.traceEvent("TestEvent")
    }
    
    tracing.shutdown()
  }
}
