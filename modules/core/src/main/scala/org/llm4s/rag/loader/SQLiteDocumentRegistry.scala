package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

import java.sql.{ Connection, DriverManager }
import scala.util.{ Try, Using }

/**
 * SQLite-based persistent document registry.
 *
 * Stores document version information in a SQLite database for
 * persistent tracking across application restarts.
 *
 * Features:
 * - File-based or in-memory storage
 * - ACID transactions
 * - Efficient lookups by document ID
 * - Timestamp tracking for last update
 *
 * @param dbPath Path to SQLite database file
 * @param connection The database connection
 */
final class SQLiteDocumentRegistry private (
  val dbPath: String,
  private val connection: Connection
) extends DocumentRegistry {

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    Using.resource(connection.createStatement()) { stmt =>
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS document_registry (
          |  doc_id TEXT PRIMARY KEY,
          |  content_hash TEXT NOT NULL,
          |  timestamp INTEGER,
          |  etag TEXT,
          |  registered_at INTEGER NOT NULL
          |)""".stripMargin
      )

      stmt.execute("CREATE INDEX IF NOT EXISTS idx_registry_hash ON document_registry(content_hash)")
    }

  override def getVersion(docId: String): Result[Option[DocumentVersion]] =
    Try {
      Using.resource(connection.prepareStatement("SELECT * FROM document_registry WHERE doc_id = ?")) { stmt =>
        stmt.setString(1, docId)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            val hash      = rs.getString("content_hash")
            val timestamp = Option(rs.getLong("timestamp")).filter(_ != 0)
            val etag      = Option(rs.getString("etag"))
            Some(DocumentVersion(hash, timestamp, etag))
          } else {
            None
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to get version: ${e.getMessage}"))

  override def register(docId: String, version: DocumentVersion): Result[Unit] =
    Try {
      val sql =
        """INSERT OR REPLACE INTO document_registry
          |(doc_id, content_hash, timestamp, etag, registered_at)
          |VALUES (?, ?, ?, ?, ?)""".stripMargin

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, docId)
        stmt.setString(2, version.contentHash)
        version.timestamp match {
          case Some(ts) => stmt.setLong(3, ts)
          case None     => stmt.setNull(3, java.sql.Types.INTEGER)
        }
        version.etag match {
          case Some(e) => stmt.setString(4, e)
          case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
        }
        stmt.setLong(5, System.currentTimeMillis())
        stmt.executeUpdate()
        ()
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to register: ${e.getMessage}"))

  override def unregister(docId: String): Result[Unit] =
    Try {
      Using.resource(connection.prepareStatement("DELETE FROM document_registry WHERE doc_id = ?")) { stmt =>
        stmt.setString(1, docId)
        stmt.executeUpdate()
        ()
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to unregister: ${e.getMessage}"))

  override def allDocumentIds(): Result[Set[String]] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery("SELECT doc_id FROM document_registry")) { rs =>
          val ids = scala.collection.mutable.Set[String]()
          while (rs.next())
            ids += rs.getString("doc_id")
          ids.toSet
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to get all IDs: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        stmt.execute("DELETE FROM document_registry")
        ()
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to clear: ${e.getMessage}"))

  override def count(): Result[Int] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM document_registry")) { rs =>
          rs.next()
          rs.getInt(1)
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to count: ${e.getMessage}"))

  /**
   * Close the database connection.
   */
  def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }
}

object SQLiteDocumentRegistry {

  /**
   * Create a file-based SQLite document registry.
   *
   * @param dbPath Path to SQLite database file
   * @return The registry or error
   */
  def apply(dbPath: String): Result[SQLiteDocumentRegistry] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
      connection.setAutoCommit(true)
      new SQLiteDocumentRegistry(dbPath, connection)
    }.toEither.left.map(e => ProcessingError("sqlite-registry", s"Failed to create registry: ${e.getMessage}"))

  /**
   * Create an in-memory SQLite document registry.
   *
   * Useful for testing or temporary storage.
   *
   * @return The registry or error
   */
  def inMemory(): Result[SQLiteDocumentRegistry] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
      connection.setAutoCommit(true)
      new SQLiteDocumentRegistry(":memory:", connection)
    }.toEither.left.map(e =>
      ProcessingError("sqlite-registry", s"Failed to create in-memory registry: ${e.getMessage}")
    )

  /**
   * Create a registry alongside a vector store database.
   *
   * Creates a registry in the same directory as the vector store,
   * with a "-registry.db" suffix.
   *
   * @param vectorStorePath Path to vector store database
   * @return The registry or error
   */
  def forVectorStore(vectorStorePath: String): Result[SQLiteDocumentRegistry] = {
    val registryPath =
      if (vectorStorePath.endsWith(".db"))
        vectorStorePath.replace(".db", "-registry.db")
      else
        vectorStorePath + "-registry.db"
    apply(registryPath)
  }
}
