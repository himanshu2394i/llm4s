package org.llm4s.util

import scala.annotation.unused

/**
 * Helpers for safe string rendering.
 *
 * Prefer these when constructing messages or toString values that may include secrets.
 */
private[llm4s] object Redaction {
  def secret(@unused value: String): String = "***"

  def secretOpt(value: Option[String]): String =
    value match {
      case Some(_) => "Some(***)"
      case None    => "None"
    }
}
