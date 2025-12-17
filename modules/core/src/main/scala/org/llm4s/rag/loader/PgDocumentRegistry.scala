package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import java.sql.Connection
import scala.util.{ Try, Using }

/**
 * PostgreSQL-based persistent document registry.
 *
 * Stores document version information in a PostgreSQL database for
 * persistent tracking across application restarts.
 *
 * Features:
 * - Connection pooling via HikariCP
 * - ACID transactions
 * - Efficient lookups by document ID
 * - Timestamp tracking for last update
 *
 * @param dataSource HikariCP connection pool
 * @param tableName Name of the registry table
 */
final class PgDocumentRegistry private (
  private val dataSource: HikariDataSource,
  val tableName: String
) extends DocumentRegistry {

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    withConnection { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        stmt.execute(
          s"""CREATE TABLE IF NOT EXISTS $tableName (
             |  doc_id TEXT PRIMARY KEY,
             |  content_hash TEXT NOT NULL,
             |  timestamp BIGINT,
             |  etag TEXT,
             |  registered_at TIMESTAMPTZ DEFAULT NOW()
             |)""".stripMargin
        )

        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_hash ON $tableName(content_hash)")
      }
      ()
    }

  override def getVersion(docId: String): Result[Option[DocumentVersion]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT * FROM $tableName WHERE doc_id = ?")) { stmt =>
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
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to get version: ${e.getMessage}"))

  override def register(docId: String, version: DocumentVersion): Result[Unit] =
    Try {
      withConnection { conn =>
        val sql =
          s"""INSERT INTO $tableName (doc_id, content_hash, timestamp, etag)
             |VALUES (?, ?, ?, ?)
             |ON CONFLICT (doc_id) DO UPDATE SET
             |  content_hash = EXCLUDED.content_hash,
             |  timestamp = EXCLUDED.timestamp,
             |  etag = EXCLUDED.etag,
             |  registered_at = NOW()""".stripMargin

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          stmt.setString(1, docId)
          stmt.setString(2, version.contentHash)
          version.timestamp match {
            case Some(ts) => stmt.setLong(3, ts)
            case None     => stmt.setNull(3, java.sql.Types.BIGINT)
          }
          version.etag match {
            case Some(e) => stmt.setString(4, e)
            case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
          }
          stmt.executeUpdate()
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to register: ${e.getMessage}"))

  override def unregister(docId: String): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE doc_id = ?")) { stmt =>
          stmt.setString(1, docId)
          stmt.executeUpdate()
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to unregister: ${e.getMessage}"))

  override def allDocumentIds(): Result[Set[String]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery(s"SELECT doc_id FROM $tableName")) { rs =>
            val ids = scala.collection.mutable.Set[String]()
            while (rs.next())
              ids += rs.getString("doc_id")
            ids.toSet
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to get all IDs: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          stmt.execute(s"TRUNCATE TABLE $tableName")
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to clear: ${e.getMessage}"))

  override def count(): Result[Int] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery(s"SELECT COUNT(*) FROM $tableName")) { rs =>
            rs.next()
            rs.getInt(1)
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to count: ${e.getMessage}"))

  /**
   * Close the connection pool.
   */
  def close(): Unit =
    if (!dataSource.isClosed) {
      dataSource.close()
    }

  private def withConnection[A](f: Connection => A): A = {
    val conn = dataSource.getConnection
    Try(f(conn)) match {
      case scala.util.Success(result) =>
        conn.close()
        result
      case scala.util.Failure(e) =>
        conn.close()
        throw e
    }
  }
}

object PgDocumentRegistry {

  /**
   * Configuration for PgDocumentRegistry.
   *
   * @param host Database host
   * @param port Database port
   * @param database Database name
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for registry (default: "document_registry")
   * @param maxPoolSize Maximum connection pool size (default: 5)
   */
  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    tableName: String = "document_registry",
    maxPoolSize: Int = 5
  ) {
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }

  /**
   * Create a PgDocumentRegistry from configuration.
   *
   * @param config The registry configuration
   * @return The registry or error
   */
  def apply(config: Config): Result[PgDocumentRegistry] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.user)
      hikariConfig.setPassword(config.password)
      hikariConfig.setMaximumPoolSize(config.maxPoolSize)
      hikariConfig.setMinimumIdle(1)
      hikariConfig.setConnectionTimeout(30000)
      hikariConfig.setIdleTimeout(600000)
      hikariConfig.setMaxLifetime(1800000)

      val dataSource = new HikariDataSource(hikariConfig)
      new PgDocumentRegistry(dataSource, config.tableName)
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to create registry: ${e.getMessage}"))

  /**
   * Create a PgDocumentRegistry from connection string.
   *
   * @param connectionString PostgreSQL connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for registry
   * @return The registry or error
   */
  def apply(
    connectionString: String,
    user: String = "postgres",
    password: String = "",
    tableName: String = "document_registry"
  ): Result[PgDocumentRegistry] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(connectionString)
      hikariConfig.setUsername(user)
      hikariConfig.setPassword(password)
      hikariConfig.setMaximumPoolSize(5)
      hikariConfig.setMinimumIdle(1)

      val dataSource = new HikariDataSource(hikariConfig)
      new PgDocumentRegistry(dataSource, tableName)
    }.toEither.left.map(e => ProcessingError("pg-registry", s"Failed to create registry: ${e.getMessage}"))

  /**
   * Create a PgDocumentRegistry with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Table name for registry
   * @return The registry or error
   */
  def local(tableName: String = "document_registry"): Result[PgDocumentRegistry] =
    apply(Config(tableName = tableName))

  /**
   * Create a PgDocumentRegistry that shares connection settings with a PgVectorStore.
   *
   * Uses the same database but a separate table for registry.
   *
   * @param vectorStoreConfig PgVectorStore configuration
   * @param tableName Table name for registry (default: "document_registry")
   * @return The registry or error
   */
  def forVectorStore(
    vectorStoreConfig: org.llm4s.vectorstore.PgVectorStore.Config,
    tableName: String = "document_registry"
  ): Result[PgDocumentRegistry] =
    apply(
      Config(
        host = vectorStoreConfig.host,
        port = vectorStoreConfig.port,
        database = vectorStoreConfig.database,
        user = vectorStoreConfig.user,
        password = vectorStoreConfig.password,
        tableName = tableName
      )
    )
}
