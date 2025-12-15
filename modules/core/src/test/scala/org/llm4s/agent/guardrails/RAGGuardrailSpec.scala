package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.rag._
import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for RAG guardrails.
 */
class RAGGuardrailSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Mock LLM Clients
  // ==========================================================================

  /**
   * Mock LLM client that returns a configurable grounding response.
   */
  class MockGroundingLLMClient(response: String) extends LLMClient {
    var lastConversation: Option[Conversation] = None

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      lastConversation = Some(conversation)
      Right(
        Completion(
          id = "test-id",
          created = System.currentTimeMillis(),
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /**
   * Mock LLM client that returns an error.
   */
  class FailingMockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Left(NetworkError("Mock network error", None, "mock://test"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // ==========================================================================
  // RAGContext Tests
  // ==========================================================================

  "RAGContext" should "combine chunks into single context" in {
    val context = RAGContext(
      query = "What is photosynthesis?",
      retrievedChunks = Seq("Plants convert sunlight.", "Chlorophyll absorbs light.")
    )

    context.combinedContext shouldBe "Plants convert sunlight.\n\nChlorophyll absorbs light."
  }

  it should "provide chunks with sources" in {
    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Chunk 1", "Chunk 2"),
      sources = Seq("doc1.pdf", "doc2.pdf")
    )

    context.chunksWithSources shouldBe Seq(
      ("Chunk 1", Some("doc1.pdf")),
      ("Chunk 2", Some("doc2.pdf"))
    )
  }

  it should "handle missing sources gracefully" in {
    val context = RAGContext(
      query = "Test",
      retrievedChunks = Seq("Chunk 1", "Chunk 2", "Chunk 3")
    )

    context.chunksWithSources should have size 3
    context.chunksWithSources.head shouldBe ("Chunk 1", None)
    context.hasCompleteSources shouldBe false
  }

  it should "detect complete sources" in {
    val context = RAGContext.withSources(
      query = "Test",
      chunks = Seq("Chunk 1", "Chunk 2"),
      sources = Seq("source1", "source2")
    )

    context.hasCompleteSources shouldBe true
  }

  // ==========================================================================
  // GroundingGuardrail Tests
  // ==========================================================================

  "GroundingGuardrail" should "pass when response is well-grounded" in {
    val response =
      """SCORE: 0.95
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: All claims are directly supported by the context.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.8)

    val context = RAGContext(
      query = "What is photosynthesis?",
      retrievedChunks = Seq("Photosynthesis is the process by which plants convert sunlight to energy.")
    )

    val result = guardrail.validateWithContext("Plants convert sunlight to energy.", context)
    result shouldBe Right("Plants convert sunlight to energy.")
  }

  it should "fail when response is not grounded" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Plants grow at night
        |EXPLANATION: The claim about night growth is not supported.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext(
      query = "When do plants grow?",
      retrievedChunks = Seq("Plants grow during the day using sunlight.")
    )

    val result = guardrail.validateWithContext("Plants grow at night.", context)
    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[ValidationError]
    result.swap.toOption.get.formatted should include("0.30")
  }

  it should "pass when score equals threshold" in {
    val response =
      """SCORE: 0.70
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Marginally grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)
    result shouldBe Right("Response")
  }

  it should "include ungrounded claims in error message when present" in {
    val response =
      """SCORE: 0.4
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Claim A is false, Claim B is made up
        |EXPLANATION: Multiple unsupported claims found.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.7)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response with false claims", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Claim A is false")
    result.swap.toOption.get.formatted should include("Claim B is made up")
  }

  it should "handle empty chunks by failing in Block mode" in {
    val mockClient = new MockGroundingLLMClient("0.9") // Won't be called
    val guardrail  = new GroundingGuardrail(mockClient, onFail = GuardrailAction.Block)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("no retrieved chunks")
  }

  it should "pass through with empty chunks in Warn mode" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = new GroundingGuardrail(mockClient, onFail = GuardrailAction.Warn)

    val context = RAGContext("Test", Seq.empty)
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "include query and chunks in LLM request" in {
    val response =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext(
      query = "What color is the sky?",
      retrievedChunks = Seq("The sky appears blue due to Rayleigh scattering.")
    )

    guardrail.validateWithContext("The sky is blue.", context)

    val conversation = mockClient.lastConversation.get
    val userMessage  = conversation.messages.collectFirst { case m: UserMessage => m }.get
    userMessage.content should include("What color is the sky?")
    userMessage.content should include("Rayleigh scattering")
    userMessage.content should include("The sky is blue")
  }

  it should "propagate LLM client errors" in {
    val mockClient = new FailingMockLLMClient()
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get shouldBe a[NetworkError]
  }

  it should "fall back to score-only parsing" in {
    val mockClient = new MockGroundingLLMClient("0.85")
    val guardrail  = GroundingGuardrail(mockClient, threshold = 0.8)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail when LLM response is unparseable" in {
    val mockClient = new MockGroundingLLMClient("I cannot evaluate this content")
    val guardrail  = GroundingGuardrail(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Could not parse")
  }

  // ==========================================================================
  // GroundingGuardrail Action Modes
  // ==========================================================================

  it should "allow processing in Warn mode when grounding fails" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Made up fact
        |EXPLANATION: Not grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = new GroundingGuardrail(mockClient, threshold = 0.7, onFail = GuardrailAction.Warn)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail in Fix mode (no auto-fix for grounding)" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Made up fact
        |EXPLANATION: Not grounded.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = new GroundingGuardrail(mockClient, threshold = 0.7, onFail = GuardrailAction.Fix)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
    result.swap.toOption.get.formatted should include("Cannot auto-fix")
  }

  // ==========================================================================
  // GroundingGuardrail Strict Mode
  // ==========================================================================

  it should "fail in strict mode when any ungrounded claims exist" in {
    val response =
      """SCORE: 0.85
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: Minor unverified detail
        |EXPLANATION: Mostly grounded but one detail can't be verified.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    // Strict mode fails on ANY ungrounded claim, even if score is high
    result.isLeft shouldBe true
  }

  it should "pass in strict mode when no ungrounded claims" in {
    val response =
      """SCORE: 0.92
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: All claims verified.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.strict(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  // ==========================================================================
  // GroundingGuardrail Presets
  // ==========================================================================

  "GroundingGuardrail.balanced" should "use 0.7 threshold" in {
    val response =
      """SCORE: 0.72
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.balanced(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  "GroundingGuardrail.lenient" should "use 0.5 threshold" in {
    val response =
      """SCORE: 0.55
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Acceptable.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.lenient(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  "GroundingGuardrail.monitoring" should "warn instead of blocking" in {
    val response =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Hallucination
        |EXPLANATION: Poor grounding.""".stripMargin

    val mockClient = new MockGroundingLLMClient(response)
    val guardrail  = GroundingGuardrail.monitoring(mockClient)

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    // Monitoring mode allows through with warning
    result shouldBe Right("Response")
  }

  // ==========================================================================
  // GroundingGuardrail Standard Validate (without context)
  // ==========================================================================

  "GroundingGuardrail.validate" should "pass through without context" in {
    val mockClient = new MockGroundingLLMClient("0.9")
    val guardrail  = GroundingGuardrail(mockClient)

    // Standard validate without context should pass through
    val result = guardrail.validate("Response")
    result shouldBe Right("Response")
  }

  // ==========================================================================
  // RAGGuardrail.all Composition
  // ==========================================================================

  "RAGGuardrail.all" should "pass when all guardrails pass" in {
    val response1 =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val response2 =
      """SCORE: 0.85
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val client1   = new MockGroundingLLMClient(response1)
    val client2   = new MockGroundingLLMClient(response2)
    val guardrail = RAGGuardrail.all(Seq(GroundingGuardrail(client1), GroundingGuardrail(client2)))

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result shouldBe Right("Response")
  }

  it should "fail when any guardrail fails" in {
    val passResponse =
      """SCORE: 0.9
        |GROUNDED: YES
        |UNGROUNDED_CLAIMS: NONE
        |EXPLANATION: Good.""".stripMargin

    val failResponse =
      """SCORE: 0.3
        |GROUNDED: NO
        |UNGROUNDED_CLAIMS: Bad claim
        |EXPLANATION: Failed.""".stripMargin

    val passClient = new MockGroundingLLMClient(passResponse)
    val failClient = new MockGroundingLLMClient(failResponse)
    val guardrail  = RAGGuardrail.all(Seq(GroundingGuardrail(passClient), GroundingGuardrail(failClient)))

    val context = RAGContext("Test", Seq("Context"))
    val result  = guardrail.validateWithContext("Response", context)

    result.isLeft shouldBe true
  }

  it should "provide descriptive name" in {
    val client    = new MockGroundingLLMClient("0.9")
    val guardrail = RAGGuardrail.all(Seq(GroundingGuardrail(client), GroundingGuardrail(client)))

    guardrail.name should include("CompositeRAGGuardrail")
    guardrail.name should include("GroundingGuardrail")
  }

  // ==========================================================================
  // GroundingResult Tests
  // ==========================================================================

  "GroundingResult" should "correctly represent grounded state" in {
    val result = GroundingResult(
      score = 0.95,
      isGrounded = true,
      ungroundedClaims = Seq.empty,
      explanation = "All claims supported"
    )

    result.isGrounded shouldBe true
    result.ungroundedClaims shouldBe empty
  }

  it should "correctly represent ungrounded state" in {
    val result = GroundingResult(
      score = 0.3,
      isGrounded = false,
      ungroundedClaims = Seq("Claim 1", "Claim 2"),
      explanation = "Multiple hallucinations"
    )

    result.isGrounded shouldBe false
    result.ungroundedClaims should have size 2
  }
}
