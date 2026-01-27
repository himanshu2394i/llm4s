package org.llm4s.vertex

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.util.Collections

/**
 * Handles authentication for Google Vertex AI.
 * Uses the google-auth-library to manage OAuth2 tokens.
 */
class VertexAIAuth(
  val projectId: String,
  val location: String,
  credentialsPath: Option[String] = None
) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val scope = "https://www.googleapis.com/auth/cloud-platform"

  private lazy val credentials: GoogleCredentials = {
    val baseCreds = credentialsPath match {
      case Some(path) => 
        logger.info(s"Loading Google credentials from file: $path")
        GoogleCredentials.fromStream(new FileInputStream(path))
      case None => 
        logger.info("Loading Google Application Default Credentials")
        GoogleCredentials.getApplicationDefault()
    }
    
    baseCreds.createScoped(Collections.singletonList(scope))
  }

  /**
   * Refresh the token if needed and return access token string.
   * This is a blocking operation.
   */
  def getAccessToken(): String = {
    credentials.refreshIfExpired()
    credentials.getAccessToken.getTokenValue
  }
}
