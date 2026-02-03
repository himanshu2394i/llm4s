package org.llm4s.llmconnect.config

import org.llm4s.util.Redaction

final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
) {
  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}
