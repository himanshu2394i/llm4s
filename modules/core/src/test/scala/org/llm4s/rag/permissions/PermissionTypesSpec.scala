package org.llm4s.rag.permissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PermissionTypesSpec extends AnyFlatSpec with Matchers {

  // PrincipalId tests
  "PrincipalId" should "identify users with positive IDs" in {
    val userId = PrincipalId(123)
    userId.isUser shouldBe true
    userId.isGroup shouldBe false
  }

  it should "identify groups with negative IDs" in {
    val groupId = PrincipalId(-5)
    groupId.isUser shouldBe false
    groupId.isGroup shouldBe true
  }

  it should "create users via factory method" in {
    val userId = PrincipalId.user(42)
    userId.value shouldBe 42
    userId.isUser shouldBe true
  }

  it should "create groups with negative IDs via factory method" in {
    val groupId = PrincipalId.group(10)
    groupId.value shouldBe -10
    groupId.isGroup shouldBe true
  }

  it should "reject zero in fromRaw" in {
    PrincipalId.fromRaw(0).isLeft shouldBe true
  }

  // ExternalPrincipal tests
  "ExternalPrincipal" should "parse user identifiers" in {
    val result = ExternalPrincipal.parse("user:john@example.com")
    result shouldBe Right(ExternalPrincipal.User("john@example.com"))
  }

  it should "parse group identifiers" in {
    val result = ExternalPrincipal.parse("group:admins")
    result shouldBe Right(ExternalPrincipal.Group("admins"))
  }

  it should "generate correct external IDs" in {
    ExternalPrincipal.User("john@example.com").externalId shouldBe "user:john@example.com"
    ExternalPrincipal.Group("admins").externalId shouldBe "group:admins"
  }

  it should "reject invalid format" in {
    ExternalPrincipal.parse("invalid").isLeft shouldBe true
    ExternalPrincipal.parse("user:").isLeft shouldBe true
    ExternalPrincipal.parse("group:").isLeft shouldBe true
  }

  // CollectionPath tests
  "CollectionPath" should "create valid paths" in {
    val result = CollectionPath.create("confluence/EN/archive")
    result.isRight shouldBe true
    result.map(_.value) shouldBe Right("confluence/EN/archive")
  }

  it should "create root paths" in {
    val result = CollectionPath.create("confluence")
    result.isRight shouldBe true
    result.map(_.isRoot) shouldBe Right(true)
  }

  it should "compute parent paths" in {
    val path   = CollectionPath.unsafe("confluence/EN/archive")
    val parent = path.parent
    parent shouldBe defined
    parent.get.value shouldBe "confluence/EN"
    parent.get.parent.map(_.value) shouldBe Some("confluence")
    parent.get.parent.flatMap(_.parent) shouldBe None
  }

  it should "compute depth correctly" in {
    CollectionPath.unsafe("confluence").depth shouldBe 1
    CollectionPath.unsafe("confluence/EN").depth shouldBe 2
    CollectionPath.unsafe("confluence/EN/archive").depth shouldBe 3
  }

  it should "reject empty paths" in {
    CollectionPath.create("").isLeft shouldBe true
    CollectionPath.create("/").isLeft shouldBe true
  }

  it should "reject invalid characters" in {
    CollectionPath.create("my collection").isLeft shouldBe true
    CollectionPath.create("path/with spaces").isLeft shouldBe true
    CollectionPath.create("path.with.dots").isLeft shouldBe true
  }

  it should "detect child relationships" in {
    val parent     = CollectionPath.unsafe("confluence")
    val child      = CollectionPath.unsafe("confluence/EN")
    val grandchild = CollectionPath.unsafe("confluence/EN/archive")

    child.isChildOf(parent) shouldBe true
    grandchild.isChildOf(parent) shouldBe false
    grandchild.isChildOf(child) shouldBe true
  }

  it should "detect descendant relationships" in {
    val ancestor   = CollectionPath.unsafe("confluence")
    val child      = CollectionPath.unsafe("confluence/EN")
    val grandchild = CollectionPath.unsafe("confluence/EN/archive")

    child.isDescendantOf(ancestor) shouldBe true
    grandchild.isDescendantOf(ancestor) shouldBe true
    grandchild.isDescendantOf(child) shouldBe true
    ancestor.isDescendantOf(child) shouldBe false
  }

  // CollectionPattern tests
  "CollectionPattern.All" should "match all collections" in {
    CollectionPattern.All.matches(CollectionPath.unsafe("anything")) shouldBe true
    CollectionPattern.All.matches(CollectionPath.unsafe("a/b/c")) shouldBe true
  }

  "CollectionPattern.Exact" should "match exact paths only" in {
    val pattern = CollectionPattern.Exact(CollectionPath.unsafe("confluence/EN"))
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe false
  }

  "CollectionPattern.ImmediateChildren" should "match only direct children" in {
    val pattern = CollectionPattern.ImmediateChildren(CollectionPath.unsafe("confluence"))
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/DE")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe false
    pattern.matches(CollectionPath.unsafe("other")) shouldBe false
  }

  "CollectionPattern.AllDescendants" should "match the prefix and all descendants" in {
    val pattern = CollectionPattern.AllDescendants(CollectionPath.unsafe("confluence"))
    pattern.matches(CollectionPath.unsafe("confluence")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/EN")) shouldBe true
    pattern.matches(CollectionPath.unsafe("confluence/EN/archive")) shouldBe true
    pattern.matches(CollectionPath.unsafe("other")) shouldBe false
    pattern.matches(CollectionPath.unsafe("confluence2")) shouldBe false
  }

  "CollectionPattern.parse" should "parse * as All" in {
    CollectionPattern.parse("*") shouldBe Right(CollectionPattern.All)
  }

  it should "parse path/* as ImmediateChildren" in {
    val result = CollectionPattern.parse("confluence/*")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/*")
  }

  it should "parse path/** as AllDescendants" in {
    val result = CollectionPattern.parse("confluence/**")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/**")
  }

  it should "parse plain path as Exact" in {
    val result = CollectionPattern.parse("confluence/EN")
    result.isRight shouldBe true
    result.map(_.patternString) shouldBe Right("confluence/EN")
  }

  // UserAuthorization tests
  "UserAuthorization" should "check principal inclusion" in {
    val auth = UserAuthorization(Set(PrincipalId(1), PrincipalId(-5)))
    auth.includes(PrincipalId(1)) shouldBe true
    auth.includes(PrincipalId(-5)) shouldBe true
    auth.includes(PrincipalId(2)) shouldBe false
  }

  it should "convert to array for SQL" in {
    val auth = UserAuthorization(Set(PrincipalId(1), PrincipalId(-5)))
    auth.asArray.sorted shouldBe Array(-5, 1)
  }

  "UserAuthorization.forUser" should "create authorization with user and groups" in {
    val auth = UserAuthorization.forUser(
      PrincipalId.user(42),
      Set(PrincipalId.group(1), PrincipalId.group(2))
    )
    auth.principalIds shouldBe Set(PrincipalId(42), PrincipalId(-1), PrincipalId(-2))
  }

  "UserAuthorization.Anonymous" should "have no principals" in {
    UserAuthorization.Anonymous.principalIds shouldBe empty
    UserAuthorization.Anonymous.isAdmin shouldBe false
  }

  "UserAuthorization.Admin" should "be an admin" in {
    UserAuthorization.Admin.isAdmin shouldBe true
  }

  // ChunkWithEmbedding tests
  "ChunkWithEmbedding" should "compute dimensions" in {
    val chunk = ChunkWithEmbedding("content", Array(0.1f, 0.2f, 0.3f), 0)
    chunk.dimensions shouldBe 3
  }

  it should "implement equals correctly" in {
    val chunk1 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    val chunk2 = ChunkWithEmbedding("content", Array(0.1f, 0.2f), 0)
    val chunk3 = ChunkWithEmbedding("different", Array(0.1f, 0.2f), 0)

    chunk1 shouldBe chunk2
    chunk1 should not be chunk3
  }
}
