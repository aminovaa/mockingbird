package ru.tinkoff.tcb.mockingbird.config

import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.Config
import com.typesafe.config.ConfigException.Generic
import com.typesafe.config.ConfigFactory
import enumeratum.*
import net.ceedubs.ficus.Ficus.*
import net.ceedubs.ficus.readers.ArbitraryTypeReader.*
import net.ceedubs.ficus.readers.EnumerationReader.*
import net.ceedubs.ficus.readers.ValueReader

final case class JsSandboxConfig(allowedClasses: Set[String] = Set())

final case class ServerConfig(
    interface: String,
    port: Int,
    allowedOrigins: Seq[String],
    healthCheckRoute: Option[String],
    sandbox: JsSandboxConfig,
    vertx: Config
)

final case class SecurityConfig(secret: String)

final case class ProxyServerAuth(user: String, password: String)

object ProxyServerType extends Enumeration {
  val Http  = Value("http")
  val Socks = Value("socks")
}

final case class ProxyServerConfig(
    `type`: ProxyServerType.Value,
    host: String,
    port: Int,
    nonProxy: Seq[String] = Seq(),
    onlyProxy: Seq[String] = Seq(),
    auth: Option[ProxyServerAuth]
)

sealed trait HttpVersion extends EnumEntry
object HttpVersion extends Enum[HttpVersion] {
  val values = findValues
  case object HTTP_1_1 extends HttpVersion
  case object HTTP_2 extends HttpVersion

  implicit val valueReader: ValueReader[HttpVersion] = ValueReader[String].map(s =>
    namesToValuesMap.get(s) match {
      case Some(v) => v
      case None    => throw new Generic(s"Cannot get instance of enum HttpVersion from the value of $s")
    }
  )
}

final case class ProxyConfig(
    excludedRequestHeaders: Seq[String],
    excludedResponseHeaders: Set[String],
    proxyServer: Option[ProxyServerConfig],
    insecureHosts: Seq[String],
    logOutgoingRequests: Boolean,
    disableAutoDecompressForRaw: Boolean,
    httpVersion: HttpVersion
)

final case class EventConfig(fetchInterval: FiniteDuration, reloadInterval: FiniteDuration)

final case class MongoConfig(uri: String, collections: MongoCollections)

final case class MongoCollections(
    stub: String,
    state: String,
    scenario: String,
    service: String,
    label: String,
    grpcStub: String,
    source: String,
    destination: String
)

final case class TracingConfig(
    required: List[String] = List.empty,
    incomingHeaders: Map[String, String] = Map.empty,
    outcomingHeaders: Map[String, String] = Map.empty,
)

final case class MockingbirdConfiguration(
    server: ServerConfig,
    security: SecurityConfig,
    mongo: MongoConfig,
    proxy: ProxyConfig,
    event: EventConfig,
    tracing: TracingConfig,
)

object MockingbirdConfiguration {
  def load(): MockingbirdConfiguration =
    load(ConfigFactory.load().getConfig("ru.tinkoff.tcb"))

  def load(config: Config): MockingbirdConfiguration =
    MockingbirdConfiguration(
      config.as[ServerConfig]("server"),
      config.as[SecurityConfig]("security"),
      config.as[MongoConfig]("db.mongo"),
      config.as[ProxyConfig]("proxy"),
      config.as[EventConfig]("event"),
      config.as[TracingConfig]("tracing"),
    )

  private lazy val conf = load()

  val server: ULayer[ServerConfig]     = ZLayer.succeed(conf.server)
  val security: ULayer[SecurityConfig] = ZLayer.succeed(conf.security)
  val mongo: ULayer[MongoConfig]       = ZLayer.succeed(conf.mongo)
  val proxy: ULayer[ProxyConfig]       = ZLayer.succeed(conf.proxy)
  val event: ULayer[EventConfig]       = ZLayer.succeed(conf.event)
  val tracing: ULayer[TracingConfig]   = ZLayer.succeed(conf.tracing)
}
