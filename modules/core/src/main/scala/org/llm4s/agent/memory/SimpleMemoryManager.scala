package org.llm4s.agent.memory

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.time.Instant

/**
 * Simple implementation of MemoryManager.
 *
 * This implementation provides basic memory management without
 * requiring an LLM for entity extraction or consolidation.
 * It's suitable for most use cases where you don't need
 * automatic entity recognition.
 *
 * For advanced features like LLM-powered entity extraction,
 * use LLMMemoryManager instead.
 *
 * @param store The underlying memory store
 * @param config Configuration options
 */
final case class SimpleMemoryManager private (
  override val store: MemoryStore,
  config: MemoryManagerConfig
) extends MemoryManager {

  override def recordMessage(
    message: Message,
    conversationId: String,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val role = message match {
      case _: UserMessage      => "user"
      case _: AssistantMessage => "assistant"
      case _: SystemMessage    => "system"
      case _: ToolMessage      => "tool"
    }

    val memory = Memory
      .fromConversation(message.content, role, Some(conversationId))
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordConversation(
    messages: Seq[Message],
    conversationId: String
  ): Result[MemoryManager] =
    messages.foldLeft[Result[MemoryManager]](Right(this)) { (acc, message) =>
      acc.flatMap(_.recordMessage(message, conversationId, None))
    }

  override def recordEntityFact(
    entityId: EntityId,
    entityName: String,
    fact: String,
    entityType: String,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .forEntity(entityId, entityName, fact, entityType)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordUserFact(
    fact: String,
    userId: Option[String],
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .userFact(fact, userId)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordKnowledge(
    content: String,
    source: String,
    metadata: Map[String, String]
  ): Result[MemoryManager] = {
    val memory = Memory
      .fromKnowledge(content, source)
      .withMetadata(metadata)

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def recordTask(
    description: String,
    outcome: String,
    success: Boolean,
    importance: Option[Double]
  ): Result[MemoryManager] = {
    val memory = Memory
      .fromTask(description, outcome, success)
      .copy(importance = importance.orElse(Some(config.defaultImportance)))

    store.store(memory).map(newStore => copy(store = newStore))
  }

  override def getRelevantContext(
    query: String,
    maxTokens: Int,
    filter: MemoryFilter
  ): Result[String] = {
    // Estimate ~4 chars per token
    val approxMaxChars = maxTokens * 4

    for {
      scored <- store.search(query, topK = 20, filter)
    } yield formatMemoriesAsContext(scored.map(_.memory), approxMaxChars)
  }

  override def getConversationContext(
    conversationId: String,
    maxMessages: Int
  ): Result[String] =
    for {
      memories <- store.getConversation(conversationId, maxMessages)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val lines = memories.map { memory =>
          val role = memory.getMetadata("role").getOrElse("unknown")
          s"[$role]: ${memory.content}"
        }
        s"Previous conversation:\n${lines.mkString("\n")}"
      }

  override def getEntityContext(entityId: EntityId): Result[String] =
    for {
      memories <- store.getEntityMemories(entityId)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val entityName = memories.headOption
          .flatMap(_.getMetadata("entity_name"))
          .getOrElse(entityId.value)

        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about $entityName:\n${facts.mkString("\n")}"
      }

  override def getUserContext(userId: Option[String]): Result[String] = {
    val filter = userId match {
      case Some(id) =>
        MemoryFilter.ByType(MemoryType.UserFact) && MemoryFilter.ByMetadata("user_id", id)
      case None =>
        MemoryFilter.ByType(MemoryType.UserFact)
    }

    for {
      memories <- store.recall(filter)
    } yield
      if (memories.isEmpty) {
        ""
      } else {
        val facts = memories.map(m => s"- ${m.content}")
        s"Known facts about the user:\n${facts.mkString("\n")}"
      }
  }

  override def consolidateMemories(
    olderThan: Instant,
    minCount: Int
  ): Result[MemoryManager] =
    // Simple implementation: just return self unchanged
    // Full implementation would summarize old memories using an LLM
    Right(this)

  override def extractEntities(
    text: String,
    conversationId: Option[String]
  ): Result[MemoryManager] =
    // Simple implementation: no entity extraction
    // Full implementation would use NLP or LLM to extract entities
    Right(this)

  override def stats: Result[MemoryStats] =
    for {
      total             <- store.count()
      conversationCount <- store.count(MemoryFilter.conversations)
      entityCount       <- store.count(MemoryFilter.entities)
      knowledgeCount    <- store.count(MemoryFilter.knowledge)
      userFactCount     <- store.count(MemoryFilter.userFacts)
      taskCount         <- store.count(MemoryFilter.tasks)
      allMemories       <- store.recall(MemoryFilter.All, Int.MaxValue)
    } yield {
      val byType = Map[MemoryType, Long](
        MemoryType.Conversation -> conversationCount,
        MemoryType.Entity       -> entityCount,
        MemoryType.Knowledge    -> knowledgeCount,
        MemoryType.UserFact     -> userFactCount,
        MemoryType.Task         -> taskCount
      ).filter(_._2 > 0)

      val timestamps = allMemories.map(_.timestamp)
      val embedded   = allMemories.count(_.isEmbedded)

      // Count distinct entities and conversations
      val distinctEntities = allMemories
        .flatMap(_.getMetadata("entity_id"))
        .distinct
        .size

      val distinctConversations = allMemories
        .flatMap(_.getMetadata("conversation_id"))
        .distinct
        .size

      MemoryStats(
        totalMemories = total,
        byType = byType,
        entityCount = distinctEntities.toLong,
        conversationCount = distinctConversations.toLong,
        embeddedCount = embedded.toLong,
        oldestMemory = if (timestamps.isEmpty) None else Some(timestamps.min),
        newestMemory = if (timestamps.isEmpty) None else Some(timestamps.max)
      )
    }

  /**
   * Format memories as context string.
   */
  private def formatMemoriesAsContext(memories: Seq[Memory], maxChars: Int): String = {
    if (memories.isEmpty) return ""

    val sections      = memories.groupBy(_.memoryType)
    val formatted     = new StringBuilder()
    var currentLength = 0

    def addSection(title: String, mems: Seq[Memory]): Unit =
      if (mems.nonEmpty && currentLength < maxChars) {
        val header = s"\n## $title\n"
        formatted.append(header)
        currentLength += header.length

        mems.takeWhile { memory =>
          val line = s"- ${memory.content}\n"
          if (currentLength + line.length <= maxChars) {
            formatted.append(line)
            currentLength += line.length
            true
          } else false
        }
      }

    // Order sections by relevance
    sections.get(MemoryType.Knowledge).foreach(addSection("Relevant Knowledge", _))
    sections.get(MemoryType.Entity).foreach(addSection("Entity Information", _))
    sections.get(MemoryType.UserFact).foreach(addSection("User Preferences", _))
    sections.get(MemoryType.Conversation).foreach(addSection("Previous Context", _))
    sections.get(MemoryType.Task).foreach(addSection("Past Tasks", _))

    // Handle custom types
    sections.foreach {
      case (MemoryType.Custom(name), mems) => addSection(s"$name", mems)
      case _                               => // already handled
    }

    if (formatted.nonEmpty) {
      s"# Retrieved Context\n${formatted.toString.trim}"
    } else ""
  }
}

object SimpleMemoryManager {

  /**
   * Create a memory manager with an empty in-memory store.
   */
  def empty: SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.empty, MemoryManagerConfig.default)

  /**
   * Create a memory manager with custom configuration.
   */
  def apply(config: MemoryManagerConfig = MemoryManagerConfig.default): SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.empty, config)

  /**
   * Create a memory manager with a specific store.
   */
  def withStore(store: MemoryStore, config: MemoryManagerConfig = MemoryManagerConfig.default): SimpleMemoryManager =
    SimpleMemoryManager(store, config)

  /**
   * Create a memory manager for testing.
   */
  def forTesting: SimpleMemoryManager =
    SimpleMemoryManager(InMemoryStore.forTesting(), MemoryManagerConfig.testing)
}
