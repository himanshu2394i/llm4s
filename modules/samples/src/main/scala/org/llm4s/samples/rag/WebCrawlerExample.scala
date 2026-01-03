package org.llm4s.samples.rag

import org.llm4s.rag.loader._

/**
 * Example demonstrating the WebCrawlerLoader for crawling web content into RAG.
 *
 * Shows how to:
 * - Crawl documentation sites with breadth-first discovery
 * - Configure crawl depth, page limits, and patterns
 * - Respect robots.txt and rate limiting
 * - Combine web content with other document sources
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.WebCrawlerExample"
 * }}}
 *
 * NOTE: This example shows the API but doesn't perform actual crawling
 * unless you uncomment the crawling sections.
 */
object WebCrawlerExample extends App {

  println("=== Web Crawler Loader Example ===\n")

  // ========== 1. Basic WebCrawlerLoader Creation ==========
  println("1. Creating a basic WebCrawlerLoader...")

  val basicCrawler = WebCrawlerLoader("https://docs.example.com")
  println(s"   Description: ${basicCrawler.description}")
  println(s"   Seed URLs: ${basicCrawler.seedUrls}")
  println(s"   Max depth: ${basicCrawler.config.maxDepth}")
  println(s"   Max pages: ${basicCrawler.config.maxPages}")

  // ========== 2. Configuring Crawl Behavior ==========
  println("\n2. Configuring crawl behavior...")

  val configuredCrawler = WebCrawlerLoader("https://docs.example.com")
    .withMaxDepth(3)    // Follow links up to 3 levels deep
    .withMaxPages(500)  // Crawl at most 500 pages
    .withDelay(1000)    // Wait 1 second between requests
    .withTimeout(30000) // 30 second timeout per request

  println(s"   Max depth: ${configuredCrawler.config.maxDepth}")
  println(s"   Max pages: ${configuredCrawler.config.maxPages}")
  println(s"   Delay: ${configuredCrawler.config.delayMs}ms")

  // ========== 3. URL Pattern Filtering ==========
  println("\n3. Using URL patterns to control scope...")

  val patternCrawler = WebCrawlerLoader("https://docs.example.com")
    .withFollowPatterns(
      "https://docs.example.com/guide/**", // Crawl guide section
      "https://docs.example.com/api/**"    // Crawl API section
    )
    .withExcludePatterns(
      "https://docs.example.com/*/archive/**",  // Skip archive pages
      "https://docs.example.com/*/changelog/**" // Skip changelogs
    )

  println(s"   Follow patterns: ${patternCrawler.config.followPatterns.mkString(", ")}")
  println(s"   Exclude patterns: ${patternCrawler.config.excludePatterns.mkString(", ")}")

  // ========== 4. Domain Restrictions ==========
  println("\n4. Domain restrictions...")

  // By default, crawling is restricted to the seed URL domain
  val sameDomainCrawler = WebCrawlerLoader("https://docs.example.com")
  println(s"   Same domain only: ${sameDomainCrawler.config.sameDomainOnly} (default)")

  // Can disable for cross-domain crawling (use with caution)
  val crossDomainCrawler = WebCrawlerLoader("https://docs.example.com")
    .withSameDomainOnly(false)
  println(s"   Cross-domain enabled: ${!crossDomainCrawler.config.sameDomainOnly}")

  // ========== 5. robots.txt Handling ==========
  println("\n5. robots.txt respect...")

  val politeBot = WebCrawlerLoader("https://example.com")
    .withRobotsTxt(true) // Default - respects robots.txt
  println(s"   Respects robots.txt: ${politeBot.config.respectRobotsTxt}")

  // Can disable for testing (not recommended for production)
  val testBot = WebCrawlerLoader("https://example.com")
    .withRobotsTxt(false)
  println(s"   Ignores robots.txt: ${!testBot.config.respectRobotsTxt}")

  // ========== 6. Preset Configurations ==========
  println("\n6. Using preset configurations...")

  // Polite crawler for documentation sites
  val politeLoader = WebCrawlerLoader.forDocs("https://docs.example.com")
  println(
    s"   forDocs: depth=${politeLoader.config.maxDepth}, " +
      s"pages=${politeLoader.config.maxPages}, delay=${politeLoader.config.delayMs}ms"
  )

  // Single page only (no link following)
  val singlePageLoader = WebCrawlerLoader.singlePage("https://example.com/specific-page")
  println(
    s"   singlePage: depth=${singlePageLoader.config.maxDepth}, " +
      s"pages=${singlePageLoader.config.maxPages}"
  )

  // ========== 7. Adding Metadata ==========
  println("\n7. Adding metadata to crawled documents...")

  val metadataCrawler = WebCrawlerLoader("https://docs.example.com")
    .withMetadata(
      Map(
        "source"     -> "documentation",
        "category"   -> "api-reference",
        "crawl_date" -> java.time.LocalDate.now.toString
      )
    )

  println(s"   Metadata: ${metadataCrawler.metadata}")

  // ========== 8. Multiple Seed URLs ==========
  println("\n8. Crawling from multiple seed URLs...")

  val multiSeedCrawler = WebCrawlerLoader(
    "https://docs.example.com/getting-started",
    "https://docs.example.com/tutorials",
    "https://docs.example.com/api"
  )
    .withSeed("https://docs.example.com/faq") // Add another seed

  println(s"   Seed URLs: ${multiSeedCrawler.seedUrls.size}")
  multiSeedCrawler.seedUrls.foreach(url => println(s"     - $url"))

  // ========== 9. Combining with Other Loaders ==========
  println("\n9. Combining with other document loaders...")

  val webDocs = WebCrawlerLoader("https://docs.example.com")
    .withMaxPages(100)

  val localDocs = TextLoader
    .builder()
    .add("local-1", "Local documentation content")
    .add("local-2", "Additional local notes")
    .build()

  val combined = webDocs ++ localDocs
  println(s"   Combined loader: ${combined.description}")

  // ========== 10. Query Parameter Handling ==========
  println("\n10. Query parameter handling...")

  // By default, query params are stripped (deduplication)
  val defaultParams = WebCrawlerLoader("https://example.com")
  println(s"   Include query params: ${defaultParams.config.includeQueryParams} (default)")
  println("   URLs like /page?id=1 and /page?id=2 treated as same page")

  // Can include query params for dynamic sites
  val withParams = WebCrawlerLoader("https://example.com")
    .withQueryParams(true)
  println(s"   Include query params: ${withParams.config.includeQueryParams}")
  println("   URLs like /page?id=1 and /page?id=2 treated as different pages")

  // ========== 11. Example RAG Integration ==========
  println("\n11. RAG integration example (code only, not executed)...")
  println("")
  println("    // Build-time crawl integration:")
  println("    val rag = RAG.builder()")
  println("      .withEmbeddings(EmbeddingProvider.OpenAI)")
  println("      .withDocuments(WebCrawlerLoader.forDocs(\"https://docs.example.com\"))")
  println("      .build()")
  println("      .toOption.get")
  println("")
  println("    // Runtime crawl integration:")
  println("    val stats = rag.ingest(")
  println("      WebCrawlerLoader(\"https://newdocs.example.com\")")
  println("        .withMaxPages(200)")
  println("    )")
  println("    println(s\"Crawled $${stats.map(_.successful).getOrElse(0)} pages\")")

  // ========== 12. Content Types ==========
  println("\n12. Content type filtering...")

  val htmlOnlyCrawler = WebCrawlerLoader("https://example.com")
  println(s"   Accepted types: ${htmlOnlyCrawler.config.acceptContentTypes.mkString(", ")}")
  println("   Non-HTML content (PDFs, images, etc.) is automatically skipped")

  // ========== 13. Memory Management ==========
  println("\n13. Memory management...")

  val safeCrawler = WebCrawlerLoader("https://example.com")
    .withMaxQueueSize(10000) // Limit URL queue size
    .withMaxPages(1000)      // Limit total pages

  println(s"   Max queue size: ${safeCrawler.config.maxQueueSize}")
  println("   Prevents unbounded memory usage during large crawls")

  println("\n=== Example Complete ===")
  println("\nTo perform actual crawling, configure environment variables and uncomment")
  println("the RAG integration code above.")
}
