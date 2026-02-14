package org.llm4s.imagegeneration.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.imagegeneration._
import org.llm4s.imagegeneration.provider.HttpClient

class StabilityImageClientSpec extends AnyFunSuite with Matchers {

  private val validConfig =
    StabilityConfig(apiKey = "test-key")

  private val httpClient =
    HttpClient.createHttpClient(validConfig)

  private val client =
    new StabilityImageClient(validConfig, httpClient)

  test("should reject empty prompt") {
    val result = client.generateImages("", 1)
    result.isLeft shouldBe true

    result match {
      case Left(_: ValidationError) => succeed
      case _                        => fail("Expected ValidationError")
    }
  }

  test("should reject count less than 1") {
    val result = client.generateImages("hello", 0)
    result.isLeft shouldBe true

    result match {
      case Left(_: ValidationError) => succeed
      case _                        => fail("Expected ValidationError")
    }
  }

  test("should reject count greater than 10") {
    val result = client.generateImages("hello", 11)
    result.isLeft shouldBe true

    result match {
      case Left(_: ValidationError) => succeed
      case _                        => fail("Expected ValidationError")
    }
  }

  test("health should return unhealthy when apiKey is empty") {

    val badConfig =
      StabilityConfig(apiKey = "")

    val badHttpClient =
      HttpClient.createHttpClient(badConfig)

    val badClient =
      new StabilityImageClient(badConfig, badHttpClient)

    val result = badClient.health()

    result.isRight shouldBe true

    result match {
      case Right(status) =>
        status.status shouldBe HealthStatus.Unhealthy
      case _ =>
        fail("Expected Right(ServiceStatus)")
    }
  }
}
