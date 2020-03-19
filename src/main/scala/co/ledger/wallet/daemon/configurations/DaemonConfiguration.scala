package co.ledger.wallet.daemon.configurations

import java.util.Locale

import com.typesafe.config.ConfigFactory
import slick.jdbc.JdbcProfile

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

object DaemonConfiguration {
  private val config = ConfigFactory.load()
  private val PERMISSION_CREATE_USER: Int = 0x01
  private val DEFAULT_AUTH_TOKEN_DURATION: Int = 3600 * 1000 // 30 seconds
  private val DEFAULT_SYNC_INTERVAL: Int = 24 // 24 hours
  private val DEFAULT_SYNC_INITIAL_DELAY: Int = 300 // 5 minutes
  // Default value for keeping alive connections inside the client connection pool
  private val DEFAULT_CLIENT_CONNECTION_TTL: Int = 120 // 120 seconds
  // Retry policy inside a connection pool
  private val DEFAULT_CLIENT_CONNECTION_RETRY_TTL: Int = 3 // Seconds to make retries
  private val DEFAULT_CLIENT_CONNECTION_RETRY_MIN: Int = 5 // Number of minimum retries per second
  private val DEFAULT_CLIENT_CONNECTION_RETRY_PERCENT: Double = 1.0D // 100% of queries would be retried at least one time
  private val DEFAULT_CLIENT_CONNECTION_RETRY_BACKOFF: Int = 50 // Linear Backoff policy delta ms

  /*
   * We set the value of RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET to be large enough
   * for use in Vault, but not infinity.
   *
   * The value we use if equal to the number of valid XRP Sequences produced in
   * one year, assuming a Sequence finality time of 3 seconds.
   *
   * See https://ledgerhq.atlassian.net/browse/LLC-346 for more information.
   */
  private val RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET: Int = 10512000

  val proxy: Option[Proxy] = {
    if (config.getBoolean("proxy.enabled")) {
      Some(Proxy(config.getString("proxy.host"), config.getInt("proxy.port")))
    } else {
      None
    }
  }

  val adminUsers: Seq[(String, String)] = if (config.hasPath("demo_users")) {
    val usersConfig = config.getConfigList("demo_users").asScala
    for {
      userConfig <- usersConfig
    } yield (userConfig.getString("username"), userConfig.getString("password"))

  } else {
    List[(String, String)]()
  }

  val whiteListUsers: Seq[(String, Int)] = if (config.hasPath("whitelist")) {
    val usersConfig = config.getConfigList("whitelist")
    val users = new ListBuffer[(String, Int)]()
    for (i <- 0 until usersConfig.size()) {
      val userConfig = usersConfig.get(i)
      val pubKey = userConfig.getString("key").toUpperCase(Locale.US)
      val permissions = if (Try(userConfig.getBoolean("account_creation")).getOrElse(false)) PERMISSION_CREATE_USER else 0
      users += ((pubKey, permissions))
    }
    users.toList
  } else {
    List[(String, Int)]()
  }

  val authTokenDuration: Int =
    if (config.hasPath("authentication.token_duration")) {
      config.getInt("authentication.token_duration_in_seconds") * 1000
    }
    else {
      DEFAULT_AUTH_TOKEN_DURATION
    }

  val dbProfileName: String = Try(config.getString("database_engine")).toOption.getOrElse("sqlite3")

  val dbProfile: JdbcProfile = dbProfileName match {
    case "sqlite3" =>
      slick.jdbc.SQLiteProfile
    case "postgres" =>
      slick.jdbc.PostgresProfile
    case "h2mem1" =>
      slick.jdbc.H2Profile
    case others => throw new Exception(s"Unknown database backend $others")
  }

  val isWhiteListDisabled: Boolean = if (!config.hasPath("disable_whitelist")) false else config.getBoolean("disable_whitelist")

  val updateWalletConfig: Boolean = if (config.hasPath("update_wallet_config")) config.getBoolean("update_wallet_config") else false

  object Synchronization {
    val initialDelay = if (config.hasPath("synchronization.initial_delay_in_seconds")) {
      config.getInt("synchronization.initial_delay_in_seconds")
    }
    else {
      DEFAULT_SYNC_INITIAL_DELAY
    }
    val interval = if (config.hasPath("synchronization.interval_in_hours")) {
      config.getInt("synchronization.interval_in_hours")
    }
    else {
      DEFAULT_SYNC_INTERVAL
    }
  }

  val realTimeObserverOn: Boolean =
    if (config.hasPath("realtimeobservation")) {
      config.getBoolean("realtimeobservation")
    }
    else {
      false
    }

  // The expire time in minutes of the pagination token used for querying operations
  val paginationTokenTtlMin: Int =
    if (config.hasPath("pagination_token.ttl_min")) {
      config.getInt("pagination_token.ttl_min")
    }
    else {
      3
    }

  // The maximum size of pagination token cache
  val paginationTokenMaxSize: Long =
    if (config.hasPath("pagination_token.ttl_min")) {
      config.getInt("pagination_token.max_size")
    }
    else {
      1000 * 1000
    }

  // The expire time in minutes of the balance per account
  val balanceCacheTtlMin: Int =
    if (config.hasPath("balance.cache.ttl_min")) {
      config.getInt("balance.cache.ttl_min")
    }
    else {
      1
    }

  // The core pool operation size
  val corePoolOpSizeFactor: Int =
    if (config.hasPath("core.threads.ops.factor")) {
      config.getInt("core.threads.ops.factor")
    }
    else {
      4
    }

  // The maximum size of pagination token cache
  val balanceCacheMaxSize: Long =
    if (config.hasPath("balance.cache.max_size")) {
      config.getInt("balance.cache.max_size")
    }
    else {
      1000
    }

  val isPrintCoreLibLogsEnabled: Boolean = config.hasPath("debug.print_core_logs") && config.getBoolean("debug.print_core_logs")

  lazy val coreDataPath: String = Try(config.getString("core_data_path")).getOrElse("./core_data")

  val explorer: ExplorerConfig = {
    val explorer = config.getConfig("explorer")
    val api = explorer.getConfig("api")
    val connectionPoolSize = api.getInt("connection_pool_size")
    val fallbackTimeout = api.getInt("fallback_timeout")
    val retryTtl = if (api.hasPath("client_connection_retry_ttl")) {
      api.getInt("client_connection_retry_ttl")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_TTL
    }
    val retryMin = if (api.hasPath("client_connection_retry_min")) {
      api.getInt("client_connection_retry_min")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_MIN
    }
    val retryPercent = if (api.hasPath("client_connection_retry_percent")) {
      api.getDouble("client_connection_retry_percent")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_PERCENT
    }
    val retryBackoffDelta = if (api.hasPath("client_connection_retry_backoff_delta")) {
      api.getInt("client_connection_retry_backoff_delta")
    } else {
      DEFAULT_CLIENT_CONNECTION_RETRY_BACKOFF
    }
    val connectionPoolTtl = if (api.hasPath("client_connection_connection_pool_ttl")) {
      api.getInt("client_connection_connection_ttl")
    } else {
      DEFAULT_CLIENT_CONNECTION_TTL
    }
    val paths = api.getConfigList("paths").asScala.toList.map { path =>
      val currency = path.getString("currency")
      val host = path.getString("host")
      val port = path.getInt("port")
      val fallback = Try(path.getString("fallback")).toOption
      val disableSyncToken = Try(path.getBoolean("disable_sync_token")).getOrElse(false)

      currency -> PathConfig(host, port, disableSyncToken, fallback)
    }.toMap
    val ws = explorer.getObject("ws").unwrapped().asScala.toMap.mapValues(_.toString)
    ExplorerConfig(
      ApiConfig(fallbackTimeout, paths),
      ClientConnectionConfig(connectionPoolSize, retryBackoffDelta, connectionPoolTtl, retryTtl, retryMin, retryPercent), ws)
  }

  val rippleLastLedgerSequenceOffset: Int = {
    if (config.hasPath("ripple_last_ledger_sequence_offset")) {
      config.getInt("ripple_last_ledger_sequence_offset")
    }
    else {
      RIPPLE_LAST_LEDGER_SEQUENCE_OFFSET
    }
  }

  case class ApiConfig(fallbackTimeout: Int, paths: Map[String, PathConfig])

  case class PathConfig(host: String, port: Int, disableSyncToken: Boolean, fallback: Option[String]) {
    def filterPrefix: PathConfig = {
      PathConfig(host.replaceFirst(".+?://", ""), port, disableSyncToken, fallback.map(_.replaceFirst(".+?://", "")))
    }
  }

  case class ClientConnectionConfig(connectionPoolSize: Int, // Maximum concurrent connection inside a connection pool
                                    retryBackoff: Int, // In millis
                                    connectionTtl: Int, // Seconds
                                    retryTtl: Int, // Seconds
                                    retryMin: Int, // minimum retry per second
                                    retryPercent: Double)

  case class ExplorerConfig(api: ApiConfig, client: ClientConnectionConfig, ws: Map[String, String])

  case class Proxy(host: String, port: Int)

}
