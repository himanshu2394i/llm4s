package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.NotFoundError

/**
 * Factory for creating VectorStore instances.
 *
 * Supports creating stores from configuration or explicit parameters.
 * Backend selection is based on the provider name.
 *
 * Currently supported backends:
 * - "sqlite" - SQLite-based storage (default)
 * - "pgvector" - PostgreSQL with pgvector extension
 *
 * Future backends (roadmap):
 * - "qdrant" - Qdrant vector database
 * - "milvus" - Milvus vector database
 * - "pinecone" - Pinecone cloud service
 */
object VectorStoreFactory {

  /**
   * Supported vector store backends.
   */
  sealed trait Backend {
    def name: String
  }

  object Backend {
    case object SQLite   extends Backend { val name = "sqlite"   }
    case object PgVector extends Backend { val name = "pgvector" }
    // Future backends:
    // case object Qdrant extends Backend { val name = "qdrant" }
    // case object Milvus extends Backend { val name = "milvus" }
    // case object Pinecone extends Backend { val name = "pinecone" }

    def fromString(s: String): Option[Backend] = s.toLowerCase match {
      case "sqlite"   => Some(SQLite)
      case "pgvector" => Some(PgVector)
      case _          => None
    }

    val all: Seq[Backend] = Seq(SQLite, PgVector)
  }

  /**
   * Configuration for creating a vector store.
   *
   * @param backend The storage backend to use
   * @param path Path to database file (for file-based backends)
   * @param connectionString Connection string (for remote backends)
   * @param options Additional backend-specific options
   */
  final case class Config(
    backend: Backend = Backend.SQLite,
    path: Option[String] = None,
    connectionString: Option[String] = None,
    options: Map[String, String] = Map.empty
  ) {

    /**
     * Create with SQLite backend.
     */
    def withSQLite(path: String): Config =
      copy(backend = Backend.SQLite, path = Some(path))

    /**
     * Create with pgvector backend.
     */
    def withPgVector(connectionString: String): Config =
      copy(backend = Backend.PgVector, connectionString = Some(connectionString))

    /**
     * Create in-memory store (SQLite).
     */
    def inMemory: Config =
      copy(backend = Backend.SQLite, path = None)

    /**
     * Add a configuration option.
     */
    def withOption(key: String, value: String): Config =
      copy(options = options + (key -> value))
  }

  object Config {

    /**
     * Default configuration (in-memory SQLite).
     */
    val default: Config = Config()

    /**
     * Configuration for file-based SQLite.
     */
    def sqlite(path: String): Config = Config(Backend.SQLite, Some(path))

    /**
     * Configuration for in-memory SQLite.
     */
    val inMemory: Config = Config(Backend.SQLite, None)

    /**
     * Configuration for pgvector.
     *
     * @param connectionString JDBC connection string (jdbc:postgresql://...)
     * @param tableName Optional table name (default: "vectors")
     */
    def pgvector(connectionString: String, tableName: String = "vectors"): Config =
      Config(Backend.PgVector, connectionString = Some(connectionString), options = Map("tableName" -> tableName))
  }

  /**
   * Create a vector store from configuration.
   *
   * @param config The store configuration
   * @return The vector store or error
   */
  def create(config: Config): Result[VectorStore] = config.backend match {
    case Backend.SQLite =>
      config.path match {
        case Some(path) => SQLiteVectorStore(path)
        case None       => SQLiteVectorStore.inMemory()
      }

    case Backend.PgVector =>
      val tableName = config.options.getOrElse("tableName", "vectors")
      config.connectionString match {
        case Some(connStr) =>
          val user     = config.options.getOrElse("user", "postgres")
          val password = config.options.getOrElse("password", "")
          PgVectorStore(connStr, user, password, tableName)
        case None =>
          // Use default local connection
          PgVectorStore.local(tableName)
      }

    // Future backends would be handled here:
    // case Backend.Qdrant => QdrantVectorStore(config.connectionString.getOrElse(...))
  }

  /**
   * Create a vector store from a provider name.
   *
   * @param provider The provider name (e.g., "sqlite", "pgvector")
   * @param path Optional path for file-based backends
   * @param connectionString Optional connection string for remote backends
   * @return The vector store or error
   */
  def create(
    provider: String,
    path: Option[String] = None,
    connectionString: Option[String] = None
  ): Result[VectorStore] =
    Backend.fromString(provider) match {
      case Some(backend) =>
        create(Config(backend, path, connectionString))
      case None =>
        Left(
          NotFoundError(
            s"Unknown vector store provider: $provider. Supported: ${Backend.all.map(_.name).mkString(", ")}",
            provider
          )
        )
    }

  /**
   * Create an in-memory vector store.
   *
   * Useful for testing or temporary storage.
   *
   * @return The vector store or error
   */
  def inMemory(): Result[VectorStore] =
    SQLiteVectorStore.inMemory()

  /**
   * Create a file-based SQLite vector store.
   *
   * @param path Path to the database file
   * @return The vector store or error
   */
  def sqlite(path: String): Result[VectorStore] =
    SQLiteVectorStore(path)

  /**
   * Create a pgvector store with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Table name for vectors (default: "vectors")
   * @return The vector store or error
   */
  def pgvector(tableName: String = "vectors"): Result[VectorStore] =
    PgVectorStore.local(tableName)

  /**
   * Create a pgvector store with explicit connection settings.
   *
   * @param connectionString JDBC connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for vectors
   * @return The vector store or error
   */
  def pgvector(
    connectionString: String,
    user: String,
    password: String,
    tableName: String
  ): Result[VectorStore] =
    PgVectorStore(connectionString, user, password, tableName)
}
