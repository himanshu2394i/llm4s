package org.llm4s.llmconnect.model

import org.llm4s.types.Result
import upickle.default.{ macroRW, ReadWriter => RW }

/**
 * Represents the message stream in a conversation.
 *  Typically this will be a sequence of system prompt, then a series of user message and assistant responses.
 *  After the system message we have a user message.  The next message is the assistant response.
 *  If the conversation is ongoing, the next message will be a user message, or if the previous AssistantMessage requested
 *  one or more tool calls it will be followed by ToolMessages in response to each requested tool.
 *
 * @param messages Sequence of messages in the conversation.
 */
case class Conversation(messages: Seq[Message]) {

  /**
   * Add a message and return a new Conversation
   */
  def addMessage(message: Message): Conversation =
    Conversation(messages :+ message)

  /**
   * Add multiple messages and return a new Conversation
   */
  def addMessages(newMessages: Seq[Message]): Conversation =
    Conversation(messages ++ newMessages)

  /**
   * Get the last message in the conversation.
   *
   * @return Some(Message) if conversation has messages, None if empty
   */
  def lastMessage: Option[Message] = messages.lastOption

  /**
   * Get count of messages.
   *
   * @return Number of messages in the conversation
   */
  def messageCount: Int = messages.length

  /**
   * Filter messages by role.
   *
   * @param role The message role to filter by
   * @return Sequence of messages matching the role
   */
  def filterByRole(role: MessageRole): Seq[Message] =
    messages.filter(_.role == role)
}

object Conversation {

  implicit val rw: RW[Conversation] = macroRW

  /**
   * Create conversation from single message - FIXED MISSING METHOD
   */
  def create(message: Message): Result[Conversation] =
    Message.validateConversation(List(message)).map(_ => Conversation(messages = List(message)))

  /**
   * Create conversation from multiple messages - FIXED MISSING METHOD
   */
  def create(messages: Message*): Result[Conversation] = create(messages.toList)

  def create(messages: List[Message]): Result[Conversation] =
    Message.validateConversation(messages).map(_ => Conversation(messages = messages))

  /**
   * Create empty conversation
   */
  def empty(): Conversation = Conversation(messages = List.empty)

  /**
   * Create a conversation from system and user prompts (most common pattern).
   * Validates both messages.
   *
   * @param systemPrompt The system message content
   * @param userPrompt The user message content
   * @return Result containing the validated conversation or validation error
   */
  def fromPrompts(systemPrompt: String, userPrompt: String): Result[Conversation] =
    create(List(SystemMessage(systemPrompt), UserMessage(userPrompt)))

  /**
   * Create a single-user-message conversation (for simple queries).
   * Validates the message.
   *
   * @param prompt The user message content
   * @return Result containing the validated conversation or validation error
   */
  def userOnly(prompt: String): Result[Conversation] =
    create(UserMessage(prompt))

  /**
   * Create a system-only conversation (for system prompts).
   * Validates the message.
   *
   * @param prompt The system message content
   * @return Result containing the validated conversation or validation error
   */
  def systemOnly(prompt: String): Result[Conversation] =
    create(SystemMessage(prompt))
}
