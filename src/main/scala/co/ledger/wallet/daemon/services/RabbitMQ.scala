package co.ledger.wallet.daemon.services

import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import com.rabbitmq.client.{BuiltinExchangeType, ConnectionFactory}
import com.twitter.inject.Logging
import javax.inject.Singleton

import scala.collection.mutable

@Singleton
class RabbitMQ() extends Logging {
  private val conn = {
    val factory = new ConnectionFactory
    factory.setUri(DaemonConfiguration.rabbitMQUri)
    val conn = factory.newConnection
    info(s"RabbitMQ: connected to ${conn.getAddress.toString}")
    conn
  }
  private val chan = conn.createChannel()
  private val declaredExchanges = mutable.HashSet[String]()

  // Channel is not recommended to be used concurrently, hence synchronized
  def publish(exchangeName: String, routingKeys: List[String], payload: Array[Byte]): Unit = synchronized {
    if (!declaredExchanges.contains(exchangeName)) {
      chan.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC)
      declaredExchanges += exchangeName
    }
    chan.basicPublish(exchangeName, routingKeys.mkString("."), null, payload)
  }

}
