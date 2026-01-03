package org.llm4s.rag.loader.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class RobotsTxtParserSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit =
    RobotsTxtParser.clearCache()

  "RobotsTxtParser.parse" should "parse simple disallow rules" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Disallow: /admin/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/private/")
    robots.disallowRules should contain("/admin/")
    robots.isAllowed("/public/page") shouldBe true
    robots.isAllowed("/private/data") shouldBe false
    robots.isAllowed("/admin/users") shouldBe false
  }

  it should "parse allow rules" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Allow: /private/public/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/private/secret") shouldBe false
    robots.isAllowed("/private/public/page") shouldBe true
  }

  it should "prefer longer matching rules" in {
    val content =
      """User-agent: *
        |Disallow: /a
        |Allow: /a/b
        |Disallow: /a/b/c
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/a/page") shouldBe false
    robots.isAllowed("/a/b/page") shouldBe true
    robots.isAllowed("/a/b/c/page") shouldBe false
  }

  it should "parse crawl-delay" in {
    val content =
      """User-agent: *
        |Crawl-delay: 10
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")
    robots.crawlDelay shouldBe Some(10)
  }

  it should "use specific user-agent rules over wildcard" in {
    val content =
      """User-agent: *
        |Disallow: /all/
        |
        |User-agent: LLM4S-Crawler
        |Disallow: /specific/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/specific/")
    robots.disallowRules should not contain "/all/"
  }

  it should "support multiple user-agent lines in a group" in {
    val content =
      """User-agent: BotA
        |User-agent: LLM4S-Crawler
        |Disallow: /private/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/private/data") shouldBe false
  }

  it should "not fall back to wildcard when specific group has no rules" in {
    val content =
      """User-agent: *
        |Disallow: /all/
        |
        |User-agent: LLM4S-Crawler
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules shouldBe empty
    robots.isAllowed("/all/blocked") shouldBe true
  }

  it should "fall back to wildcard when no specific match" in {
    val content =
      """User-agent: *
        |Disallow: /general/
        |
        |User-agent: OtherBot
        |Disallow: /other/
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.disallowRules should contain("/general/")
    robots.disallowRules should not contain "/other/"
  }

  it should "treat regex metacharacters as literals" in {
    val content =
      """User-agent: *
        |Disallow: /private.html
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")

    robots.isAllowed("/privateXhtml") shouldBe true
    robots.isAllowed("/private.html") shouldBe false
  }

  it should "ignore comments" in {
    val content =
      """# This is a comment
        |User-agent: * # inline comment
        |Disallow: /private/ # block private
        |""".stripMargin

    val robots = RobotsTxtParser.parse(content, "LLM4S-Crawler/1.0")
    robots.disallowRules should contain("/private/")
  }

  it should "handle empty content" in {
    val robots = RobotsTxtParser.parse("", "LLM4S-Crawler/1.0")
    robots.allowRules shouldBe empty
    robots.disallowRules shouldBe empty
    robots.isAllowed("/any/path") shouldBe true
  }

  "RobotsTxt.isAllowed" should "allow all paths by default" in {
    val robots = RobotsTxtParser.RobotsTxt.empty
    robots.isAllowed("/any/path") shouldBe true
    robots.isAllowed("/") shouldBe true
  }

  it should "handle root disallow" in {
    val robots = RobotsTxtParser.RobotsTxt(disallowRules = Seq("/"))
    robots.isAllowed("/any/path") shouldBe false
    robots.isAllowed("/") shouldBe false
  }

  it should "allow ties go to allow rules" in {
    val robots = RobotsTxtParser.RobotsTxt(
      allowRules = Seq("/path"),
      disallowRules = Seq("/path")
    )
    robots.isAllowed("/path") shouldBe true
  }
}
