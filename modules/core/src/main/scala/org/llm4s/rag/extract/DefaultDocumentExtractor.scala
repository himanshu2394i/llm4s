package org.llm4s.rag.extract

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.tika.Tika
import org.slf4j.LoggerFactory

import java.io.{ ByteArrayInputStream, InputStream }
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }

/**
 * Default implementation of DocumentExtractor using Apache Tika, PDFBox, and POI.
 *
 * Supports:
 * - PDF documents via Apache PDFBox
 * - Word documents (.docx) via Apache POI
 * - Plain text files with UTF-8 encoding
 * - HTML, XML, JSON via Apache Tika
 * - Other formats via Tika fallback
 */
object DefaultDocumentExtractor extends DocumentExtractor {

  private val logger = LoggerFactory.getLogger(getClass)
  private val tika   = new Tika()

  // MIME type constants
  private val PdfMime  = "application/pdf"
  private val DocxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  private val DocMime  = "application/msword"

  override def extract(
    content: Array[Byte],
    filename: String,
    mimeType: Option[String] = None
  ): Result[ExtractedDocument] = {
    val detectedMime = mimeType.getOrElse(detectMimeType(content, filename))
    val format       = DocumentFormat.fromMimeType(detectedMime)

    logger.debug(s"Extracting document: $filename (MIME: $detectedMime, format: ${format.name})")

    extractText(content, detectedMime, filename).map { text =>
      ExtractedDocument(
        text = text,
        metadata = Map(
          "filename"   -> filename,
          "mimeType"   -> detectedMime,
          "byteLength" -> content.length.toString
        ),
        format = format
      )
    }
  }

  override def extractFromStream(
    input: InputStream,
    filename: String,
    mimeType: Option[String] = None
  ): Result[ExtractedDocument] =
    // For streams, we need to read into bytes for MIME detection
    // In the future, we could optimize by buffering only what's needed for detection
    Try(input.readAllBytes()) match {
      case Success(bytes) => extract(bytes, filename, mimeType)
      case Failure(ex) =>
        Left(ProcessingError("document-extraction", s"Failed to read input stream: ${ex.getMessage}", Some(ex)))
    }

  override def detectMimeType(content: Array[Byte], filename: String): String =
    tika.detect(content, filename)

  override def canExtract(mimeType: String): Boolean = {
    val mime = mimeType.toLowerCase
    mime == PdfMime ||
    mime == DocxMime ||
    mime == DocMime ||
    mime.startsWith("text/") ||
    mime == "application/json" ||
    mime == "application/xml"
  }

  private def extractText(content: Array[Byte], mimeType: String, filename: String): Result[String] =
    mimeType match {
      case PdfMime =>
        extractPDF(content, filename)

      case DocxMime =>
        extractDocx(content, filename)

      case DocMime =>
        // Legacy .doc format - try Tika
        extractWithTika(content, filename)

      case mt if mt.startsWith("text/") =>
        extractPlainText(content, filename)

      case "application/json" | "application/xml" =>
        extractPlainText(content, filename)

      case _ =>
        // Try Tika as fallback
        extractWithTika(content, filename)
    }

  private def extractPDF(content: Array[Byte], filename: String): Result[String] =
    Try {
      Using.resource(Loader.loadPDF(content)) { doc =>
        val stripper = new PDFTextStripper()
        stripper.getText(doc)
      }
    } match {
      case Success(text) =>
        logger.debug(s"PDF extraction successful for $filename: ${text.length} characters")
        Right(text)
      case Failure(ex) =>
        logger.error(s"PDF extraction failed for $filename: ${ex.getMessage}", ex)
        Left(
          ProcessingError("pdf-extraction", s"Failed to extract text from PDF '$filename': ${ex.getMessage}", Some(ex))
        )
    }

  private def extractDocx(content: Array[Byte], filename: String): Result[String] =
    Try {
      Using.resource(new ByteArrayInputStream(content)) { bis =>
        Using.resource(new XWPFDocument(bis))(doc => doc.getParagraphs.asScala.map(_.getText).mkString("\n"))
      }
    } match {
      case Success(text) =>
        logger.debug(s"DOCX extraction successful for $filename: ${text.length} characters")
        Right(text)
      case Failure(ex) =>
        logger.error(s"DOCX extraction failed for $filename: ${ex.getMessage}", ex)
        Left(
          ProcessingError(
            "docx-extraction",
            s"Failed to extract text from DOCX '$filename': ${ex.getMessage}",
            Some(ex)
          )
        )
    }

  private def extractPlainText(content: Array[Byte], filename: String): Result[String] =
    Try(new String(content, StandardCharsets.UTF_8)) match {
      case Success(text) =>
        logger.debug(s"Plain text extraction successful for $filename: ${text.length} characters")
        Right(text)
      case Failure(ex) =>
        logger.error(s"Plain text extraction failed for $filename: ${ex.getMessage}", ex)
        Left(ProcessingError("text-extraction", s"Failed to read text from '$filename': ${ex.getMessage}", Some(ex)))
    }

  private def extractWithTika(content: Array[Byte], filename: String): Result[String] =
    Try {
      Using.resource(new ByteArrayInputStream(content))(bis => tika.parseToString(bis))
    } match {
      case Success(text) if text.trim.nonEmpty =>
        logger.debug(s"Tika extraction successful for $filename: ${text.length} characters")
        Right(text)
      case Success(_) =>
        logger.warn(s"Tika extraction returned empty text for $filename")
        Left(ProcessingError("tika-extraction", s"Tika extracted empty content from '$filename'", None))
      case Failure(ex) =>
        logger.error(s"Tika extraction failed for $filename: ${ex.getMessage}", ex)
        Left(ProcessingError("tika-extraction", s"Failed to extract text from '$filename': ${ex.getMessage}", Some(ex)))
    }
}
