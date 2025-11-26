package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.core._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CoreToolsSpec extends AnyFlatSpec with Matchers {

  "DateTimeTool" should "return current date/time in default timezone" in {
    val params = ujson.Obj()
    val result = DateTimeTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val dateTime = result.toOption.get
    dateTime.timezone shouldBe "UTC"
    dateTime.iso8601.nonEmpty shouldBe true
    dateTime.components.year >= 2024 shouldBe true
  }

  it should "support custom timezone" in {
    val params = ujson.Obj("timezone" -> "America/New_York")
    val result = DateTimeTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val dateTime = result.toOption.get
    dateTime.timezone shouldBe "America/New_York"
  }

  it should "return error for invalid timezone" in {
    val params = ujson.Obj("timezone" -> "Invalid/Timezone")
    val result = DateTimeTool.tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("timezone")
  }

  "CalculatorTool" should "perform addition" in {
    val params = ujson.Obj("operation" -> "add", "a" -> 5.0, "b" -> 3.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 8.0
  }

  it should "perform subtraction" in {
    val params = ujson.Obj("operation" -> "subtract", "a" -> 10.0, "b" -> 4.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 6.0
  }

  it should "perform multiplication" in {
    val params = ujson.Obj("operation" -> "multiply", "a" -> 6.0, "b" -> 7.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 42.0
  }

  it should "perform division" in {
    val params = ujson.Obj("operation" -> "divide", "a" -> 15.0, "b" -> 3.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 5.0
  }

  it should "handle division by zero" in {
    val params = ujson.Obj("operation" -> "divide", "a" -> 10.0, "b" -> 0.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("zero")
  }

  it should "perform square root" in {
    val params = ujson.Obj("operation" -> "sqrt", "a" -> 16.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 4.0
  }

  it should "handle negative square root" in {
    val params = ujson.Obj("operation" -> "sqrt", "a" -> -4.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("negative")
  }

  it should "calculate power" in {
    val params = ujson.Obj("operation" -> "power", "a" -> 2.0, "b" -> 3.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 8.0
  }

  it should "calculate percentage" in {
    val params = ujson.Obj("operation" -> "percentage", "a" -> 200.0, "b" -> 15.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    result.toOption.get.result shouldBe 30.0
  }

  it should "reject unknown operation" in {
    val params = ujson.Obj("operation" -> "unknown", "a" -> 1.0)
    val result = CalculatorTool.tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("Unknown operation")
  }

  "UUIDTool" should "generate a UUID" in {
    val params = ujson.Obj()
    val result = UUIDTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val uuidResult = result.toOption.get
    uuidResult.uuids.size shouldBe 1
    uuidResult.uuids.head.uuid.length shouldBe 36
    uuidResult.uuids.head.version shouldBe 4
  }

  it should "generate multiple UUIDs" in {
    val params = ujson.Obj("count" -> 5)
    val result = UUIDTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val uuidResult = result.toOption.get
    uuidResult.uuids.size shouldBe 5
    uuidResult.uuids.map(_.uuid).distinct.size shouldBe 5 // All unique
  }

  it should "generate standard format UUID" in {
    val params = ujson.Obj("format" -> "standard")
    val result = UUIDTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val uuidResult = result.toOption.get
    uuidResult.uuids.head.uuid.length shouldBe 36
    uuidResult.uuids.head.uuid should include("-")
  }

  it should "generate compact format UUID" in {
    val params = ujson.Obj("format" -> "compact")
    val result = UUIDTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val uuidResult = result.toOption.get
    uuidResult.uuids.head.uuid.length shouldBe 32
    (uuidResult.uuids.head.uuid should not).include("-")
  }

  "JSONTool" should "parse valid JSON" in {
    val params = ujson.Obj(
      "operation" -> "parse",
      "json"      -> """{"name": "test", "value": 42}"""
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val jsonResult = result.toOption.get
    jsonResult.success shouldBe true
    jsonResult.result.obj.nonEmpty shouldBe true
  }

  it should "return error for invalid JSON" in {
    val params = ujson.Obj(
      "operation" -> "parse",
      "json"      -> """{"invalid": }"""
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("Invalid JSON")
  }

  it should "format JSON with pretty printing" in {
    val params = ujson.Obj(
      "operation" -> "format",
      "json"      -> """{"a":1,"b":2}"""
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val jsonResult = result.toOption.get
    jsonResult.success shouldBe true
    jsonResult.formatted should include("\n")
  }

  it should "query JSON with path" in {
    val params = ujson.Obj(
      "operation" -> "query",
      "json"      -> """{"user": {"name": "Alice", "age": 30}}""",
      "path"      -> "user.name"
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val jsonResult = result.toOption.get
    jsonResult.success shouldBe true
    jsonResult.formatted should include("Alice")
  }

  it should "query array elements" in {
    val params = ujson.Obj(
      "operation" -> "query",
      "json"      -> """{"items": [1, 2, 3]}""",
      "path"      -> "items[1]"
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val jsonResult = result.toOption.get
    jsonResult.success shouldBe true
    jsonResult.formatted shouldBe "2"
  }

  it should "validate JSON" in {
    val params = ujson.Obj(
      "operation" -> "validate",
      "json"      -> """{"valid": true}"""
    )
    val result = JSONTool.tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val jsonResult = result.toOption.get
    jsonResult.success shouldBe true
    jsonResult.formatted shouldBe "Valid JSON"
  }
}
