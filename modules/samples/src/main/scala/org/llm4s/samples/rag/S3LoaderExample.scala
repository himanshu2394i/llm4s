package org.llm4s.samples.rag

import org.llm4s.rag.loader.s3.{ S3DocumentSource, S3Loader }
import org.llm4s.rag.{ EmbeddingProvider, RAG, RAGSearchResult }
import org.llm4s.rag.RAG.RAGConfigOps
import org.slf4j.LoggerFactory

/**
 * Example demonstrating S3 document loading and ingestion.
 *
 * This example shows how to:
 * - Load documents from an S3 bucket with full PDF/DOCX support
 * - Use S3Loader for simple integration with RAG.sync()
 * - Configure S3 access with credentials and region
 * - Set up LocalStack for local testing
 *
 * Prerequisites:
 * - AWS credentials configured (env vars, ~/.aws/credentials, or IAM role)
 * - An S3 bucket with documents to ingest
 * - For local testing: LocalStack running (`docker run -d -p 4566:4566 localstack/localstack`)
 *
 * Environment variables:
 * - AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY (or use IAM roles)
 * - OPENAI_API_KEY (for RAG embeddings)
 */
object S3LoaderExample extends App {

  private val logger = LoggerFactory.getLogger(getClass)

  println("=" * 60)
  println("S3 Document Loader Example")
  println("=" * 60)

  // Configuration - adjust these for your environment
  val bucket        = sys.env.getOrElse("S3_BUCKET", "my-documents")
  val prefix        = sys.env.getOrElse("S3_PREFIX", "docs/")
  val region        = sys.env.getOrElse("AWS_REGION", "us-east-1")
  val useLocalStack = sys.env.getOrElse("USE_LOCALSTACK", "false").toBoolean

  println(s"\nConfiguration:")
  println(s"  Bucket: $bucket")
  println(s"  Prefix: $prefix")
  println(s"  Region: $region")
  println(s"  LocalStack: $useLocalStack")

  // Create RAG pipeline using the builder API
  val ragResult = RAG
    .builder()
    .withEmbeddings(EmbeddingProvider.OpenAI)
    .build()

  ragResult match {
    case Left(err) =>
      logger.error(s"Failed to create RAG pipeline: ${err.message}")
      println(s"\nError: ${err.message}")
      println("\nMake sure you have set:")
      println("  - OPENAI_API_KEY")
      sys.exit(1)

    case Right(rag) =>
      try
        runExample(rag, bucket, prefix, region, useLocalStack)
      finally
        rag.close()
  }

  def runExample(
    rag: RAG,
    bucket: String,
    prefix: String,
    region: String,
    useLocalStack: Boolean
  ): Unit = {
    println("\nRAG pipeline created successfully.")

    // Create S3 document loader
    val loader = if (useLocalStack) {
      println("\nUsing LocalStack for local testing...")
      println("Make sure LocalStack is running: docker run -d -p 4566:4566 localstack/localstack")
      S3Loader.forLocalStack(bucket, prefix)
    } else {
      S3Loader(
        bucket = bucket,
        prefix = prefix,
        region = region
      )
    }

    println(s"\nLoader created: ${loader.description}")

    // Sync documents from S3
    println("\n--- Syncing documents from S3 ---")
    rag.sync(loader) match {
      case Right(stats) =>
        println(s"\nSync completed successfully!")
        println(s"  Documents added:     ${stats.added}")
        println(s"  Documents updated:   ${stats.updated}")
        println(s"  Documents deleted:   ${stats.deleted}")
        println(s"  Documents unchanged: ${stats.unchanged}")
        println(s"  Total:               ${stats.total}")

        if (stats.hasChanges) {
          println("\nDocuments were indexed. You can now query them.")
        } else {
          println("\nNo changes detected since last sync.")
        }

      case Left(err) =>
        logger.error(s"Sync failed: ${err.message}")
        println(s"\nSync failed: ${err.message}")
        println("\nCommon issues:")
        println("  - Check AWS credentials are configured")
        println("  - Verify the bucket exists and is accessible")
        println("  - Check network connectivity to S3")
    }

    // Example query (if RAG has documents)
    println("\n--- Example Query ---")
    rag.query("What topics are covered in the documents?", Some(3)) match {
      case Right(results) if results.nonEmpty =>
        println(s"Found ${results.size} relevant chunks:")
        results.zipWithIndex.foreach { case (result: RAGSearchResult, idx: Int) =>
          println(s"\n${idx + 1}. Score: ${result.score}")
          println(s"   ${result.content.take(200)}...")
        }
      case Right(_) =>
        println("No results found. The index may be empty.")
      case Left(err) =>
        println(s"Query failed: ${err.message}")
    }

    println("\n" + "=" * 60)
    println("Example complete")
    println("=" * 60)
  }
}

/**
 * Advanced example showing custom S3 configuration.
 */
object S3LoaderAdvancedExample extends App {

  println("=" * 60)
  println("S3 Document Loader - Advanced Configuration")
  println("=" * 60)

  // Example 1: Custom file extensions
  println("\n--- Example 1: Custom Extensions ---")
  val pdfOnlyLoader = S3Loader(
    bucket = "my-bucket",
    prefix = "reports/",
    extensions = Set("pdf") // Only PDF files
  )
  println(s"PDF-only loader: ${pdfOnlyLoader.description}")

  // Example 2: Explicit credentials
  println("\n--- Example 2: Explicit Credentials ---")
  val withCredsLoader = S3Loader.withCredentials(
    bucket = "private-bucket",
    accessKeyId = sys.env.getOrElse("AWS_ACCESS_KEY_ID", "test"),
    secretAccessKey = sys.env.getOrElse("AWS_SECRET_ACCESS_KEY", "test"),
    region = "eu-west-1"
  )
  println(s"Credentials loader: ${withCredsLoader.description}")

  // Example 3: Using the source directly for advanced configuration
  println("\n--- Example 3: Advanced Source Configuration ---")
  val source = S3DocumentSource(
    bucket = "my-bucket",
    prefix = "docs/",
    extensions = Set("txt", "md", "pdf", "docx"),
    metadata = Map("environment" -> "production")
  ).withEndpoint("http://localhost:4566") // For MinIO or LocalStack

  println(s"Advanced source: ${source.description}")

  // Example 4: Combining loaders
  println("\n--- Example 4: Multi-Source Loading ---")
  val docsLoader     = S3Loader("company-docs", prefix = "public/")
  val reportsLoader  = S3Loader("company-docs", prefix = "reports/2024/")
  val combinedLoader = docsLoader ++ reportsLoader
  println(s"Combined loader: ${combinedLoader.description}")

  println("\n" + "=" * 60)
  println("Advanced examples complete")
  println("=" * 60)
}
