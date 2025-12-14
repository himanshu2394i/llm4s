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
 *
 * Future backends (roadmap):
 * - "pgvector" - PostgreSQL with pgvector extension
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
    case object SQLite extends Backend { val name = "sqlite" }
    // Future backends:
    // case object PgVector extends Backend { val name = "pgvector" }
    // case object Qdrant extends Backend { val name = "qdrant" }
    // case object Milvus extends Backend { val name = "milvus" }
    // case object Pinecone extends Backend { val name = "pinecone" }

    def fromString(s: String): Option[Backend] = s.toLowerCase match {
      case "sqlite" => Some(SQLite)
      case _        => None
    }

    val all: Seq[Backend] = Seq(SQLite)
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

    // Future backends would be handled here:
    // case Backend.PgVector => PgVectorStore(config.connectionString.getOrElse(...))
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
}
