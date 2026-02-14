package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.imagegeneration.StabilityConfig
import org.llm4s.imagegeneration.provider.BaseHttpClient
import ujson._
import java.time.Instant
import scala.util.Try

class StabilityImageClient(
    config: StabilityConfig,
    httpClient: BaseHttpClient
   )   
    extends ImageGenerationClient {

  
  

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    val result = for {
      validPrompt <- validatePrompt(prompt)
      validCount  <- validateCount(count)
      response    <- makeApiRequest(validPrompt, validCount, options)
      images      <- parseResponse(response, validPrompt, options)
    } yield images

    result
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
   if (config.apiKey.trim.isEmpty) {
    Right(
      ServiceStatus(
        status = HealthStatus.Unhealthy,
        message = "API key is missing"
      )
    )
  } else {
    Right(
      ServiceStatus(
        status = HealthStatus.Healthy,
        message = "Stability AI client configured"
      )
    )
  }
 }



  private def validatePrompt(
    prompt: String
  ): Either[ImageGenerationError, String] =
    if (prompt.trim.isEmpty)
      Left(ValidationError("Prompt cannot be empty"))
    else
      Right(prompt)

  private def validateCount(
    count: Int
  ): Either[ImageGenerationError, Int] =
    if (count < 1 || count > 10)
      Left(ValidationError("Count must be between 1 and 10"))
    else
      Right(count)
  
  private def buildPayload(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): String = {

    val body = Obj(
     "prompt" -> prompt,
     "mode" -> "text-to-image",
     "output_format" -> options.format.extension,
     "width" -> options.size.width,
     "height" -> options.size.height,
     "cfg_scale" -> options.guidanceScale,
     "steps" -> options.inferenceSteps,
     "samples" -> count
     )

    options.negativePrompt.foreach(np =>
     body("negative_prompt") = np
     )

    options.seed.foreach(s =>
     body("seed") = s
     )

    body.render()
  }


  private def makeApiRequest(
   prompt: String,
   count: Int,
   options: ImageGenerationOptions
 ): Either[ImageGenerationError, requests.Response] = {

   val payload = buildPayload(prompt, count, options)

   val result =
    Try(httpClient.post(payload))
      .toEither
      .left
      .map(e => ServiceError(e.getMessage, 500))

   result.flatMap { response =>
    response.statusCode match {
      case 200 => Right(response)
      case 401 => Left(AuthenticationError("Invalid API key"))
      case 429 => Left(RateLimitError("Rate limit exceeded"))
      case 400 => Left(ValidationError(response.text()))
      case code => Left(ServiceError(response.text(), code))
    }
   }
 }


  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    Try {
      val json      = read(response.text())
      val artifacts = json("artifacts").arr

      artifacts.map { artifact =>
        val base64Data = artifact("base64").str

        GeneratedImage(
          data = base64Data,
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = options.seed,
          filePath = None
        )
      }.toSeq
    }.toEither.left.map(ex => UnknownError(ex))
  }
}
