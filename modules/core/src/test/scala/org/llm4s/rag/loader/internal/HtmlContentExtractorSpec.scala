package org.llm4s.rag.loader.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HtmlContentExtractorSpec extends AnyFlatSpec with Matchers {

  "HtmlContentExtractor.extract" should "extract title from title tag" in {
    val html = """
      |<html>
      |<head><title>Page Title</title></head>
      |<body><p>Content</p></body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.title shouldBe "Page Title"
  }

  it should "fall back to h1 if no title tag" in {
    val html = """
      |<html>
      |<head></head>
      |<body><h1>Heading Title</h1><p>Content</p></body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.title shouldBe "Heading Title"
  }

  it should "extract meta description" in {
    val html = """
      |<html>
      |<head>
      |  <title>Title</title>
      |  <meta name="description" content="This is the description">
      |</head>
      |<body><p>Content</p></body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.description shouldBe Some("This is the description")
  }

  it should "extract og:description as fallback" in {
    val html = """
      |<html>
      |<head>
      |  <title>Title</title>
      |  <meta property="og:description" content="OG Description">
      |</head>
      |<body><p>Content</p></body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.description shouldBe Some("OG Description")
  }

  it should "extract links from anchor tags" in {
    val html = """
      |<html>
      |<body>
      |  <a href="/relative">Relative</a>
      |  <a href="http://example.com/absolute">Absolute</a>
      |  <a href="https://other.com/page">External</a>
      |</body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.links should contain("http://example.com/relative")
    result.links should contain("http://example.com/absolute")
    result.links should contain("https://other.com/page")
  }

  it should "filter out non-http links" in {
    val html = """
      |<html>
      |<body>
      |  <a href="mailto:test@example.com">Email</a>
      |  <a href="javascript:void(0)">JavaScript</a>
      |  <a href="ftp://files.example.com">FTP</a>
      |  <a href="http://valid.com">Valid</a>
      |</body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.links should have size 1
    result.links should contain("http://valid.com")
  }

  it should "remove navigation elements from content" in {
    val html = """
      |<html>
      |<body>
      |  <nav><a href="/home">Home</a><a href="/about">About</a></nav>
      |  <header><h1>Site Header</h1></header>
      |  <main><p>Main content here</p></main>
      |  <footer><p>Copyright 2024</p></footer>
      |</body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.content should include("Main content here")
    (result.content should not).include("Home")
    (result.content should not).include("About")
    (result.content should not).include("Copyright")
  }

  it should "remove script and style elements" in {
    val html = """
      |<html>
      |<head>
      |  <style>.hidden { display: none; }</style>
      |  <script>alert('hello');</script>
      |</head>
      |<body>
      |  <p>Visible content</p>
      |  <script>console.log('inline');</script>
      |</body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.content should include("Visible content")
    (result.content should not).include("alert")
    (result.content should not).include("console")
    (result.content should not).include("hidden")
  }

  it should "extract text from main content area" in {
    val html = """
      |<html>
      |<body>
      |  <aside>Sidebar content</aside>
      |  <main>
      |    <article>
      |      <h1>Article Title</h1>
      |      <p>Article paragraph one.</p>
      |      <p>Article paragraph two.</p>
      |    </article>
      |  </main>
      |</body>
      |</html>
      |""".stripMargin

    val result = HtmlContentExtractor.extract(html, "http://example.com/page")
    result.content should include("Article Title")
    result.content should include("Article paragraph one")
    result.content should include("Article paragraph two")
  }

  it should "handle empty HTML" in {
    val result = HtmlContentExtractor.extract("", "http://example.com/page")
    result.title shouldBe ""
    result.content shouldBe ""
    result.links shouldBe empty
  }

  "HtmlContentExtractor.extractLinksOnly" should "extract links without processing content" in {
    val html = """
      |<html>
      |<body>
      |  <a href="http://link1.com">Link 1</a>
      |  <a href="http://link2.com">Link 2</a>
      |</body>
      |</html>
      |""".stripMargin

    val links = HtmlContentExtractor.extractLinksOnly(html, "http://example.com/page")
    links should contain("http://link1.com")
    links should contain("http://link2.com")
  }
}
