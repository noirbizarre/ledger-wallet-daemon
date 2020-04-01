package co.ledger.wallet.daemon.utils

import java.util.Date

import co.ledger.wallet.daemon.ServerImpl
import co.ledger.wallet.daemon.services.ECDSAService
import com.lambdaworks.codec.Base64
import com.twitter.finagle.http.{Response, Status}
import com.twitter.finatra.http.EmbeddedHttpServer
import com.twitter.inject.server.FeatureTest
import org.bitcoinj.core.Sha256Hash

trait APIFeatureTest extends FeatureTest {
  override val server = new EmbeddedHttpServer(new ServerImpl)

  def defaultHeaders: Map[String, String] = lwdBasicAuthorisationHeader("whitelisted")

  def parse[A](response: Response)(implicit manifest: Manifest[A]): A = server.mapper.parse[A](response)

  def assertWalletCreation(poolName: String, walletName: String, currencyName: String, expected: Status): Response = {
    server.httpPost(path = s"/pools/$poolName/wallets",
      postBody = s"""{\"currency_name\":\"$currencyName\",\"wallet_name\":\"$walletName\"}""",
      headers = defaultHeaders,
      andExpect = expected)
  }

  def assertWalletNativeSegwitCreation(poolName: String, walletName: String, currencyName: String, expected: Status): Response = {
    server.httpPost(path = s"/pools/$poolName/wallets",
      postBody = s"""{\"currency_name\":\"$currencyName\",\"wallet_name\":\"$walletName\",\"is_native_segwit\":true}""",
      headers = defaultHeaders,
      andExpect = expected)
  }

  def getPools(): Response = {
    server.httpGet("/pools", headers = defaultHeaders, andExpect = Status.Ok)
  }

  def getPool(poolName: String): Response = {
    getPool(poolName, Status.Ok)
  }

  def getPool(poolName: String, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName", headers = defaultHeaders, andExpect = expected)
  }

  def createPool(poolName: String, expected: Status = Status.Ok): Response = {
    server.httpPost("/pools", s"""{"pool_name":"$poolName"}""", headers = defaultHeaders, andExpect = expected)
  }

  def getAddresses(poolName: String, walletName: String, account: Int, from: Int, to: Int, expected: Status = Status.Ok): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$account/addresses?from=$from&to=$to", headers = defaultHeaders, andExpect = expected)
  }

  def deletePool(poolName: String): Response = {
    server.httpDelete(s"/pools/$poolName", "", headers = defaultHeaders)
  }

  def assertSyncPool(expected: Status): Response = {
    server.httpPost("/pools/operations/synchronize", "", headers = defaultHeaders, andExpect = expected)
  }

  def assertSyncAccount(poolName: String, walletName: String, accIdx: Int): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts/$accIdx/operations/synchronize", "", headers = defaultHeaders, andExpect = Status.Ok)
  }

  protected def assertCreateAccount(accountCreationBody: String, poolName: String, walletName: String, expected: Status): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts", accountCreationBody, headers = defaultHeaders, andExpect = expected)
  }

  protected def assertCreateAccountExtended(accountCreationBody: String, poolName: String, walletName: String, expected: Status): Response = {
    server.httpPost(s"/pools/$poolName/wallets/$walletName/accounts/extended", accountCreationBody, headers = defaultHeaders, andExpect = expected)
  }

  protected def deleteAccount(poolName: String, walletName: String, accountIdx: Int, expected: Status): Response = {
    server.httpDelete(s"/pools/$poolName/wallets/$walletName/accounts/$accountIdx", headers = defaultHeaders, andExpect = expected)
  }

  private def lwdBasicAuthorisationHeader(seedName: String, time: Date = new Date()) = {
    val ecdsa = server.injector.instance(classOf[ECDSAService])
    val privKey = Sha256Hash.hash(FixturesUtils.seed(seedName).getBytes)
    val pubKey = ecdsa.computePublicKey(privKey)
    val timestamp = time.getTime / 1000
    val message = Sha256Hash.hash(s"LWD: $timestamp\n".getBytes)
    val signed = ecdsa.sign(message, privKey)
    Map(
      "authorization" -> s"LWD ${Base64.encode(s"${HexUtils.valueOf(pubKey)}:$timestamp:${HexUtils.valueOf(signed)}".getBytes).mkString}"
    )
  }
}
