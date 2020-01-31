package co.ledger.wallet.daemon.filters

import java.nio.charset.StandardCharsets
import java.util.{Base64, Date}

import co.ledger.wallet.daemon.services.AuthenticationService.{AuthContext, AuthContextContext}
import co.ledger.wallet.daemon.utils.HexUtils
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future

class LWDAutenticationFilter extends SimpleFilter[Request, Response] {

  private val AUTH_STRING_PATTERN = "([0-9a-fA-F]+):([0-9]+):([0-9a-fA-F]+)".r
  private val AUTHORIZATION_PREFIX: Int = 4

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    request.headerMap.get("authorization").filter(_ contains "LWD") foreach {(string) =>
      val auth = new String(Base64.getDecoder.decode(string.substring(AUTHORIZATION_PREFIX).getBytes(StandardCharsets.UTF_8)))
      val AUTH_STRING_PATTERN(pubKey, timestamp, signature) = auth
      AuthContextContext.setContext(request, AuthContext(HexUtils.valueOf(pubKey), timestamp.toLong, HexUtils.valueOf(signature)))
    }
    service(request)
  }

}

class HackLWDAutenticationFilter extends SimpleFilter[Request, Response] {
   override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
        val pubKey = request.headerMap.get("pubKey").get
        AuthContextContext.setContext(request, AuthContext(HexUtils.valueOf(pubKey), new Date().getTime, HexUtils.valueOf("1234")))
        service(request)
   }
}
