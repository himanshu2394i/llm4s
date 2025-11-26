package org.llm4s.toolapi.builtin.http

/**
 * Configuration for HTTP tool.
 *
 * @param allowedDomains Optional list of allowed domains. If None, all domains are allowed.
 * @param blockedDomains List of domains that are always blocked.
 * @param maxResponseSize Maximum response size in bytes.
 * @param timeoutMs Request timeout in milliseconds.
 * @param followRedirects Whether to follow HTTP redirects.
 * @param maxRedirects Maximum number of redirects to follow.
 * @param allowedMethods HTTP methods that are allowed.
 * @param userAgent User-Agent header to use.
 */
case class HttpConfig(
  allowedDomains: Option[Seq[String]] = None,
  blockedDomains: Seq[String] = Seq("localhost", "127.0.0.1", "0.0.0.0", "::1"),
  maxResponseSize: Long = 10 * 1024 * 1024, // 10 MB
  timeoutMs: Int = 30000,                   // 30 seconds
  followRedirects: Boolean = true,
  maxRedirects: Int = 5,
  allowedMethods: Seq[String] = Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"),
  userAgent: String = "llm4s-http-tool/1.0"
) {

  /**
   * Check if a domain is allowed.
   */
  def isDomainAllowed(domain: String): Boolean = {
    val normalizedDomain = domain.toLowerCase.stripPrefix("www.")

    // Check blocked domains first
    if (
      blockedDomains.exists(blocked =>
        normalizedDomain == blocked.toLowerCase || normalizedDomain.endsWith("." + blocked.toLowerCase)
      )
    ) {
      return false
    }

    // If allowlist is defined, domain must be in it
    allowedDomains match {
      case Some(allowed) =>
        allowed.exists { a =>
          val normalizedAllowed = a.toLowerCase.stripPrefix("www.")
          normalizedDomain == normalizedAllowed || normalizedDomain.endsWith("." + normalizedAllowed)
        }
      case None => true
    }
  }

  /**
   * Check if a method is allowed.
   */
  def isMethodAllowed(method: String): Boolean =
    allowedMethods.map(_.toUpperCase).contains(method.toUpperCase)
}

object HttpConfig {

  /**
   * Create a read-only configuration that only allows GET and HEAD requests.
   */
  def readOnly(
    allowedDomains: Option[Seq[String]] = None,
    blockedDomains: Seq[String] = Seq("localhost", "127.0.0.1", "0.0.0.0", "::1")
  ): HttpConfig =
    HttpConfig(
      allowedDomains = allowedDomains,
      blockedDomains = blockedDomains,
      allowedMethods = Seq("GET", "HEAD", "OPTIONS")
    )

  /**
   * Create a restrictive configuration with explicit domain allowlist.
   */
  def restricted(allowedDomains: Seq[String]): HttpConfig =
    HttpConfig(allowedDomains = Some(allowedDomains))
}
