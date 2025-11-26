package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.filesystem._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Paths }

class FileSystemToolsSpec extends AnyFlatSpec with Matchers {

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  // Use system temp directory for cross-platform compatibility
  private val testDir = {
    val tempRoot = System.getProperty("java.io.tmpdir")
    val dir      = Paths.get(tempRoot, "llm4s-test-" + System.currentTimeMillis())
    Files.createDirectories(dir)
    dir
  }

  "FileConfig" should "block system paths by default" in {
    assume(!isWindows, "Unix system paths not available on Windows")
    val config = FileConfig()

    config.isPathAllowed(Paths.get("/etc/passwd")) shouldBe false
    config.isPathAllowed(Paths.get("/var/log/syslog")) shouldBe false
    config.isPathAllowed(Paths.get("/sys/kernel")) shouldBe false
    config.isPathAllowed(Paths.get("/proc/1/status")) shouldBe false
    config.isPathAllowed(Paths.get("/dev/null")) shouldBe false
  }

  it should "allow paths when allowedPaths is set" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = FileConfig(allowedPaths = Some(Seq("/tmp", "/home/user")))

    config.isPathAllowed(Paths.get("/tmp/file.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/home/user/doc.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/other/file.txt")) shouldBe false
  }

  it should "block paths in blocklist even if in allowlist" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = FileConfig(
      allowedPaths = Some(Seq("/")),
      blockedPaths = Seq("/etc", "/var")
    )

    config.isPathAllowed(Paths.get("/tmp/ok.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/etc/passwd")) shouldBe false
    config.isPathAllowed(Paths.get("/var/log/test")) shouldBe false
  }

  "WriteConfig" should "require explicit allowed paths" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = WriteConfig(allowedPaths = Seq("/tmp/output"))

    config.isPathAllowed(Paths.get("/tmp/output/file.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/tmp/other/file.txt")) shouldBe false
    config.isPathAllowed(Paths.get("/home/user/file.txt")) shouldBe false
  }

  "ReadFileTool" should "read existing files" in {
    val tempFile = testDir.resolve("read-test.txt")
    Files.writeString(tempFile, "Hello, World!")

    // No blocked paths, only test dir allowed
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ReadFileTool.create(config)

    val params = ujson.Obj("path" -> tempFile.toString)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val readResult = result.toOption.get
    readResult.content shouldBe "Hello, World!"
    readResult.lines shouldBe 1
    readResult.truncated shouldBe false

    Files.deleteIfExists(tempFile)
  }

  it should "deny access to blocked paths" in {
    assume(!isWindows, "Unix system paths not available on Windows")
    val config = FileConfig()
    val tool   = ReadFileTool.create(config)

    val params = ujson.Obj("path" -> "/etc/passwd")
    val result = tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("Access denied")
  }

  it should "return error for non-existent files" in {
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ReadFileTool.create(config)

    val params = ujson.Obj("path" -> (testDir.toString + "/nonexistent_file_12345.txt"))
    val result = tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("not found")
  }

  it should "limit lines when max_lines is specified" in {
    val tempFile = testDir.resolve("lines-test.txt")
    Files.writeString(tempFile, "line1\nline2\nline3\nline4\nline5")

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ReadFileTool.create(config)

    val params = ujson.Obj("path" -> tempFile.toString, "max_lines" -> 2)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val readResult = result.toOption.get
    readResult.content shouldBe "line1\nline2"
    readResult.truncated shouldBe true

    Files.deleteIfExists(tempFile)
  }

  "ListDirectoryTool" should "list directory contents" in {
    val subDir = testDir.resolve("list-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("file1.txt"))
    Files.createFile(subDir.resolve("file2.txt"))
    Files.createDirectory(subDir.resolve("subdir"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ListDirectoryTool.create(config)

    val params = ujson.Obj("path" -> subDir.toString)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val listResult = result.toOption.get
    listResult.entries.size shouldBe 3
    listResult.totalFiles shouldBe 2
    listResult.totalDirectories shouldBe 1

    // Cleanup
    Files.deleteIfExists(subDir.resolve("file1.txt"))
    Files.deleteIfExists(subDir.resolve("file2.txt"))
    Files.deleteIfExists(subDir.resolve("subdir"))
    Files.deleteIfExists(subDir)
  }

  it should "hide hidden files by default" in {
    val subDir = testDir.resolve("hidden-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("visible.txt"))
    Files.createFile(subDir.resolve(".hidden"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ListDirectoryTool.create(config)

    val params = ujson.Obj("path" -> subDir.toString)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val listResult = result.toOption.get
    listResult.entries.size shouldBe 1
    listResult.entries.head.name shouldBe "visible.txt"

    Files.deleteIfExists(subDir.resolve("visible.txt"))
    Files.deleteIfExists(subDir.resolve(".hidden"))
    Files.deleteIfExists(subDir)
  }

  it should "include hidden files when requested" in {
    val subDir = testDir.resolve("hidden-include-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("visible.txt"))
    Files.createFile(subDir.resolve(".hidden"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = ListDirectoryTool.create(config)

    val params = ujson.Obj("path" -> subDir.toString, "include_hidden" -> true)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val listResult = result.toOption.get
    listResult.entries.size shouldBe 2

    Files.deleteIfExists(subDir.resolve("visible.txt"))
    Files.deleteIfExists(subDir.resolve(".hidden"))
    Files.deleteIfExists(subDir)
  }

  "FileInfoTool" should "get file information" in {
    val tempFile = testDir.resolve("info-test.txt")
    Files.writeString(tempFile, "test content")

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = FileInfoTool.create(config)

    val params = ujson.Obj("path" -> tempFile.toString)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val infoResult = result.toOption.get
    infoResult.exists shouldBe true
    infoResult.isFile shouldBe true
    infoResult.isDirectory shouldBe false
    infoResult.size shouldBe 12
    infoResult.extension shouldBe Some("txt")

    Files.deleteIfExists(tempFile)
  }

  it should "report non-existent file info" in {
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    val tool   = FileInfoTool.create(config)

    val params = ujson.Obj("path" -> (testDir.toString + "/nonexistent_12345.txt"))
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val infoResult = result.toOption.get
    infoResult.exists shouldBe false
    infoResult.isFile shouldBe false
    infoResult.isDirectory shouldBe false
  }

  "WriteFileTool" should "write to allowed paths" in {
    val config = WriteConfig(allowedPaths = Seq(testDir.toString), allowOverwrite = true)
    val tool   = WriteFileTool.create(config)

    val outputPath = testDir.resolve("write-output.txt").toString
    val params     = ujson.Obj("path" -> outputPath, "content" -> "Hello!")
    val result     = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    val writeResult = result.toOption.get
    writeResult.bytesWritten shouldBe 6
    writeResult.created shouldBe true

    // Verify content
    Files.readString(Paths.get(outputPath)) shouldBe "Hello!"

    Files.deleteIfExists(Paths.get(outputPath))
  }

  it should "deny write to non-allowed paths" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = WriteConfig(allowedPaths = Seq("/tmp/specific"))
    val tool   = WriteFileTool.create(config)

    val params = ujson.Obj("path" -> "/tmp/other/file.txt", "content" -> "test")
    val result = tool.handler(SafeParameterExtractor(params))

    result.isLeft shouldBe true
    result.swap.toOption.get should include("Access denied")
  }

  it should "support append mode" in {
    val tempFile = testDir.resolve("append-test.txt")
    Files.writeString(tempFile, "Hello")

    val config = WriteConfig(allowedPaths = Seq(testDir.toString), allowOverwrite = true)
    val tool   = WriteFileTool.create(config)

    val params = ujson.Obj("path" -> tempFile.toString, "content" -> " World!", "append" -> true)
    val result = tool.handler(SafeParameterExtractor(params))

    result.isRight shouldBe true
    Files.readString(tempFile) shouldBe "Hello World!"

    Files.deleteIfExists(tempFile)
  }
}
