package co.ledger.wallet.daemon.api

import java.util.UUID

import co.ledger.core.TimePeriod
import co.ledger.wallet.daemon.controllers.AccountsController.UtxoAccountResponse
import co.ledger.wallet.daemon.models.{AccountDerivationView, AccountView, FreshAddressView}
import co.ledger.wallet.daemon.services.OperationQueryParams
import co.ledger.wallet.daemon.utils.APIFeatureTest
import com.fasterxml.jackson.databind.JsonNode
import com.twitter.finagle.http.{Response, Status}

class AccountsApiTest extends APIFeatureTest {

  test("AccountsApi#Get history") {
    createPool("balance_pool")
    assertWalletCreation("balance_pool", "account_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "balance_pool", "account_wallet", Status.Ok)
    history("balance_pool", "account_wallet", 0, "2017-10-12T13:38:23Z", "2018-10-12T13:38:23Z", TimePeriod.DAY.toString, Status.Ok)
    deletePool("balance_pool")
  }

  test("AccountsApi#Get history bad requests") {
    createPool("balance_pool")
    assertWalletCreation("balance_pool", "account_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "balance_pool", "account_wallet", Status.Ok)
    history("balance_pool", "account_wallet", 0, "2017-10-12", "2018-10-12T13:38:23Z", TimePeriod.DAY.toString, Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2017-10-12T13:38:23Z", "2018-10-12", TimePeriod.DAY.toString, Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2017-10-12T13:38:23Z", "2018-10-12T13:38:23Z", "TIME", Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2018-11-12T13:38:23Z", "2018-10-12T13:38:23Z", TimePeriod.DAY.toString, Status.BadRequest)
    // test if period too long
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2015-13-12T00:00:00Z", TimePeriod.HOUR.toString, Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2015-13-12T00:00:00Z", TimePeriod.DAY.toString, Status.Ok)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2019-10-12T00:00:00Z", TimePeriod.DAY.toString, Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2019-10-12T00:00:00Z", TimePeriod.WEEK.toString, Status.Ok)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2029-10-12T00:00:00Z", TimePeriod.WEEK.toString, Status.BadRequest)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2029-10-12T00:00:00Z", TimePeriod.MONTH.toString, Status.Ok)
    history("balance_pool", "account_wallet", 0, "2015-11-12T00:00:00Z", "2219-10-12T00:00:00Z", TimePeriod.MONTH.toString, Status.BadRequest)
    deletePool("balance_pool")
  }

  test("AccountsApi#Get empty accounts") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "account_wallet", "bitcoin", Status.Ok)
    val result = assertGetAccounts(None, "account_pool", "account_wallet", Status.Ok)
    val expectedResponse = List[AccountView]()
    assert(expectedResponse === parse[Seq[AccountView]](result))
    deletePool("account_pool")
  }

  test("AccountsApi#Get account with index from empty wallet") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "individual_account_wallet", "bitcoin", Status.Ok)
    assertGetAccounts(Option(1), "account_pool", "individual_account_wallet", Status.NotFound)
    deletePool("account_pool")
  }

  test("AccountsApi#Get accounts same as get individual account") {
    createPool("list_pool")
    assertWalletCreation("list_pool", "account_wallet", "bitcoin", Status.Ok)
    val expectedAccount = parse[AccountView](assertCreateAccount(CORRECT_BODY_BITCOIN, "list_pool", "account_wallet", Status.Ok))
    val actualAccount = parse[AccountView](assertGetAccounts(Option(0), "list_pool", "account_wallet", Status.Ok))
    assert(expectedAccount === actualAccount)
    val actualAccountList = parse[Seq[AccountView]](assertGetAccounts(None, "list_pool", "account_wallet", Status.Ok))
    assert(List(actualAccount) === actualAccountList)
    deletePool("list_pool")
  }

  test("AccountsApi#Get fresh addresses from account") {
    createPool("fresh_addresses_pool")
    assertWalletCreation("fresh_addresses_pool", "account_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "fresh_addresses_pool", "account_wallet", Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses("fresh_addresses_pool", "account_wallet", index = 0, Status.Ok))
    assert(addresses.nonEmpty)
    deletePool("fresh_addresses_pool")
  }

  test("AccountsApi#Get addresses by range") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "accounts_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "account_pool", "accounts_wallet", Status.Ok)
    val r = parse[Seq[FreshAddressView]](getAddresses("account_pool", "accounts_wallet", 0, 0, 1))
    assert(r.size == 4)
    deletePool("account_pool")
  }

  test("AccountsApi#Get utxo from btc account") {
    val poolName = "getUtxo_pool"
    createPool(poolName)
    assertWalletCreation(poolName, "account_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, poolName, "account_wallet", Status.Ok)
    val utxoList = parse[UtxoAccountResponse](assertGetUTXO(poolName, "account_wallet", index = 0, Status.Ok))
    // FIXME : There is no UTXO on this account so the result is empty
    assert(utxoList.utxos.isEmpty)
    assert(0 === utxoList.count)
    deletePool(poolName)
  }

  test("AccountsApi#Get account(s) from non exist pool return bad request") {
    assertCreateAccount(CORRECT_BODY_BITCOIN, "not_exist_pool", "account_wallet", Status.BadRequest)
    assertGetAccounts(None, "not_exist_pool", "account_wallet", Status.BadRequest)
    assertGetAccounts(Option(0), "not_exist_pool", "account_wallet", Status.BadRequest)
  }

  test("AccountsApi#Get account(s) from non exist wallet return bad request") {
    createPool("exist_pool")
    assertCreateAccount(CORRECT_BODY_BITCOIN, "exist_pool", "not_exist_wallet", Status.BadRequest)
    assertGetAccounts(None, "exist_pool", "not_exist_wallet", Status.BadRequest)
    assertGetAccounts(Option(0), "exist_pool", "not_exist_wallet", Status.BadRequest)
    deletePool("exist_pool")
  }

  test("AccountsApi#Get next account creation info with index return Ok") {
    createPool("info_pool")
    assertWalletCreation("info_pool", "account_wallet", "bitcoin", Status.Ok)
    val actualResult = parse[AccountDerivationView](assertGetAccountCreationInfo("info_pool", "account_wallet", Option(0), Status.Ok))
    assert(0 === actualResult.accountIndex)
    deletePool("info_pool")
  }

  test("AccountsApi#Get next account creation info without index return Ok") {
    createPool("info_pool")
    assertWalletCreation("info_pool", "account_wallet", "bitcoin", Status.Ok)
    assertGetAccountCreationInfo("info_pool", "account_wallet", None, Status.Ok)
    deletePool("info_pool")
  }

  test("AccountsApi#Create account with no pubkey") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "accounts_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(MISSING_PUBKEY_BODY, "account_pool", "accounts_wallet", Status.BadRequest)
    deletePool("account_pool")
  }

  test("AccountsApi#Create account with invalid request body") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "accounts_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(MISSING_PATH_BODY, "account_pool", "accounts_wallet", Status.BadRequest)
    deletePool("account_pool")
  }

  test("AccountsApi#Create account fail core lib validation") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "accounts_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(INVALID_ARGUMENT_BODY, "account_pool", "accounts_wallet", Status.BadRequest)
    deletePool("account_pool")
  }

  test("AccountsApi#Create account on btc testnet") {
    createPool("test_pool")
    assertWalletCreation("test_pool", "accounts_wallet", "bitcoin_testnet", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "test_pool", "accounts_wallet", Status.Ok)
    deletePool("test_pool")
  }

  test("AccountsApi#Create account with request body as invalid json") {
    createPool("account_pool")
    assertWalletCreation("account_pool", "accounts_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(INVALID_JSON, "account_pool", "accounts_wallet", Status.BadRequest)
    deletePool("account_pool")
  }

  test("AccountsApi#Create and Delete pool with wallet accounts") {
    val poolName = "delete_pool"
    val wallet1 = "account_deletePool_wal1"
    val wallet2 = "account_deletePool_wal2"
    val wallet3 = "account_deletePool_wal3"

    createPool(poolName)
    assertWalletCreation(poolName, wallet1, "bitcoin", Status.Ok) // no account associated
    assertWalletCreation(poolName, wallet2, "bitcoin", Status.Ok) // 1 account associated
    assertWalletCreation(poolName, wallet3, "bitcoin", Status.Ok) // 2 accounts
    assertCreateAccount(CORRECT_BODY_BITCOIN, poolName, wallet2, Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, poolName, wallet3, Status.Ok)
    assertCreateAccount(CORRECT_BODY_IDX1, poolName, wallet3, Status.Ok)

    val pool = getPool(poolName, Status.Ok)
    val walPoolView: JsonNode = server.mapper.objectMapper.readTree(pool.getContentString())
    assert(walPoolView.findValue("name").asText().equals(poolName))
    assert(walPoolView.findValue("wallet_count").asInt() == 3)
    info(s"Pool is = $pool and $walPoolView")
    deletePool(poolName)
    getPool(poolName, Status.NotFound)
  }

  test("AccountsApi#Create account and delete specific account") {
    val poolName = "delete_account"
    val wallet1 = "test_deleteAcc_wal1"
    val wallet2 = "test_deleteAcc_wal2"
    val wallet3 = "test_deleteAcc_wal3"

    // Create Pool wallets and accounts
    createPool(poolName)

    assertWalletCreation(poolName, wallet1, "bitcoin", Status.Ok) // no account associated
    assertWalletCreation(poolName, wallet2, "bitcoin", Status.Ok) // 1 account associated
    assertWalletCreation(poolName, wallet3, "bitcoin", Status.Ok) // 2 accounts
    assertCreateAccount(CORRECT_BODY_BITCOIN, poolName, wallet2, Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, poolName, wallet3, Status.Ok)
    assertCreateAccount(CORRECT_BODY_IDX1, poolName, wallet3, Status.Ok)

    // Sync
    assertSyncAccount(poolName, wallet2, 0)
    assertSyncAccount(poolName, wallet3, 0)
    assertSyncAccount(poolName, wallet3, 1)

    val countWall3A0 = transactionCountOf(poolName, wallet3, 0)

    // Get then Delete and check operation count for the 3 accounts
    assert(transactionCountOf(poolName, wallet2, 0) > 0)
    deleteAccount(poolName, wallet2, 0, Status.Ok)
    assert(transactionCountOf(poolName, wallet2, 0) == 0)
    assert(transactionCountOf(poolName, wallet3, 0) > 0)
    assert(transactionCountOf(poolName, wallet3, 1) > 0)

    deleteAccount(poolName, wallet3, 1, Status.Ok)
    assert(transactionCountOf(poolName, wallet2, 0) == 0)
    assert(transactionCountOf(poolName, wallet3, 0) > 0)
    assert(transactionCountOf(poolName, wallet3, 1) == 0)

    deleteAccount(poolName, wallet3, 0, Status.Ok)
    assert(transactionCountOf(poolName, wallet2, 0) == 0)
    assert(transactionCountOf(poolName, wallet3, 0) == 0)
    assert(transactionCountOf(poolName, wallet3, 1) == 0)
    assertGetAccounts(Some(0), poolName, wallet2, Status.Ok)
    assertGetAccounts(Some(0), poolName, wallet3, Status.Ok)
    assertGetAccounts(Some(1), poolName, wallet3, Status.Ok)

    // Sync again and retrieve operations for a given account only (others should stay empty)
    assertSyncAccount(poolName, wallet3, 0)
    assert(transactionCountOf(poolName, wallet2, 0) == 0)
    val countWall3A0After = transactionCountOf(poolName, wallet3, 0)
    assert(countWall3A0After > 0)
    assert(countWall3A0After == countWall3A0)
    assert(transactionCountOf(poolName, wallet3, 1) == 0) // Still unchanged
    assertGetAccounts(Some(0), poolName, wallet2, Status.Ok)
    assertGetAccounts(Some(0), poolName, wallet3, Status.Ok)
    assertGetAccounts(Some(1), poolName, wallet3, Status.Ok)
  }

  private def transactionCountOf(poolName: String, walletName: String, accIdx: Int): Int = {
    val response = assertGetAccounts(Some(accIdx), poolName, walletName, Status.Ok)
    val jsonAccount: JsonNode = server.mapper.objectMapper.readTree(response.getContentString())
    val opsJson = jsonAccount.findValue("operation_count")

    val received = Option(opsJson.findValue("RECEIVE")) match {
      case Some(v) => v.intValue()
      case _ => 0
    }

    val sent = Option(opsJson.findValue("SEND")) match {
      case Some(v) => v.intValue()
      case _ => 0
    }
    sent + received
  }

  test("AccountsApi#Get account operations") {

    def getUUID(field: String, content: Map[String, JsonNode]): Option[UUID] = {
      val idStr = content.get(field).map(_.asText())
      idStr.map(UUID.fromString)
    }

    createPool("op_pool")
    assertWalletCreation("op_pool", "op_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "op_pool", "op_wallet", Status.Ok)
    assertSyncPool(Status.Ok)
    assertGetAccountOp("op_pool", "op_wallet", 0, "noexistop", 0, Status.NotFound)
    assertGetAccountOp("op_pool", "op_wallet", 0, "bcbe563a94e70a6fe0a190d6578f5440615eb64bbd6c49b2a6b77eb9ba01963a", 0, Status.Ok)
    val response = assertGetFirstOperation(0, "op_pool", "op_wallet", Status.Ok).contentString
    assert(response.contains("ed977add08cfc6cd158e65150bcd646d7a52b60f84e15e424b617d5511aaed21"))


    val firstBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(None, None, 2, 0), Status.Ok))

    val secondBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(None, getUUID("next", firstBtch), 10, 0), Status.Ok))

    val previousOf2ndBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(getUUID("previous", secondBtch), None, 10, 0), Status.Ok))
    assert(firstBtch.get("next") === previousOf2ndBtch.get("next"))
    assert(firstBtch.get("previous") === previousOf2ndBtch.get("previous"))

    val thirdBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(None, getUUID("next", secondBtch), 5, 0), Status.Ok))

    val fourthBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(None, getUUID("next", thirdBtch), 10, 0), Status.Ok))

    val previousOf4thBtch = parse[Map[String, JsonNode]](assertGetAccountOps("op_pool", "op_wallet", 0, OperationQueryParams(getUUID("previous", fourthBtch), None, 10, 1), Status.Ok))
    assert(thirdBtch.get("next") === previousOf4thBtch.get("next"))
    assert(thirdBtch.get("previous") === previousOf4thBtch.get("previous"))
    deletePool("op_pool")
  }

  test("AccountsApi#Pool not exist") {
    createPool("op_pool_mal")
    assertWalletCreation("op_pool_mal", "op_wallet", "bitcoin", Status.Ok)
    assertCreateAccount(CORRECT_BODY_BITCOIN, "op_pool_mal", "op_wallet", Status.Ok)

    assertGetAccountOps("op_pool_non_exist", "op_wallet", 0, OperationQueryParams(None, None, 2, 0), Status.BadRequest)
    assertGetAccountOps("op_pool_mal", "op_wallet", 0, OperationQueryParams(None, Option(UUID.randomUUID), 2, 0), Status.BadRequest)
    assertGetAccountOps("op_pool_mal", "op_wallet", 0, OperationQueryParams(Option(UUID.randomUUID), None, 2, 0), Status.BadRequest)
    deletePool("op_pool_mal")
  }

  test("AccountsApi#Create eth account") {
    val poolName = "op_pool"
    val walletName = "ethWallet"
    createPool(poolName)
    assertWalletCreation(poolName, walletName, "ethereum", Status.Ok)
    assertCreateAccountExtended(CORRECT_BODY_ETH, poolName, walletName, Status.Ok)
    /*
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(None, None, 2, 0), Status.BadRequest)
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(None, Option(UUID.randomUUID), 2, 0), Status.BadRequest)
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(Option(UUID.randomUUID), None, 2, 0), Status.BadRequest)
        */

    deletePool(poolName)
  }

  test("AccountsApi#Create XRP account") {
    val poolName = "op_pool"
    val walletName = "xrpWallet"
    createPool(poolName)
    assertWalletCreation(poolName, walletName, "ripple", Status.Ok)
    assertCreateAccount(CORRECT_BODY_XRP, poolName, walletName, Status.Ok)
    val addresses = parse[Seq[FreshAddressView]](assertGetFreshAddresses(poolName, walletName, index = 0, Status.Ok))
    info(s"Here are addresses : $addresses")
    assertSyncAccount(poolName, walletName, 0)
    assertGetAccountOps(poolName, walletName, 0, OperationQueryParams(None, None, 1000, 0), Status.Ok)

    /*
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(None, None, 2, 0), Status.BadRequest)
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(None, Option(UUID.randomUUID), 2, 0), Status.BadRequest)
        assertGetAccountOps(poolName, "op_wallet", 0, OperationQueryParams(Option(UUID.randomUUID), None, 2, 0), Status.BadRequest)
        */

    deletePool(poolName)
  }

  private def assertGetAccountOp(poolName: String, walletName: String, accountIndex: Int, uid: String, fullOp: Int, expected: Status): Response = {
    val sb = new StringBuilder(s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/operations/$uid?full_op=$fullOp")
    server.httpGet(sb.toString(), headers = defaultHeaders, andExpect = expected)
  }

  private def assertGetFirstOperation(index: Int, poolName: String, walletName: String, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/operations/first", headers = defaultHeaders, andExpect = expected)
  }

  private def history(poolName: String, walletName: String, accountIndex: Int, start: String, end: String, timeInterval: String, expected: Status): Response = {
    server.httpGet(
      path = s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/history?start=$start&end=$end&time_interval=$timeInterval",
      headers = defaultHeaders, andExpect = expected)
  }

  private def assertGetAccountOps(poolName: String, walletName: String, accountIndex: Int, params: OperationQueryParams, expected: Status): Response = {
    val sb = new StringBuilder(s"/pools/$poolName/wallets/$walletName/accounts/$accountIndex/operations?")
    params.previous.foreach { p =>
      sb.append("previous=" + p.toString + "&")
    }
    params.next.foreach { n =>
      sb.append("next=" + n.toString + "&")
    }
    sb.append(s"batch=${params.batch}&full_op=${params.fullOp}")
    server.httpGet(sb.toString(), headers = defaultHeaders, andExpect = expected)
  }

  private def assertGetAccounts(index: Option[Int], poolName: String, walletName: String, expected: Status): Response = {
    index match {
      case None => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts", headers = defaultHeaders, andExpect = expected)
      case Some(i) => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$i", headers = defaultHeaders, andExpect = expected)
    }
  }

  private def assertGetAccountCreationInfo(poolName: String, walletName: String, index: Option[Int], expected: Status): Response = {
    index match {
      case None => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/next", headers = defaultHeaders, andExpect = expected)
      case Some(i) => server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/next?account_index=$i", headers = defaultHeaders, andExpect = expected)
    }
  }

  private def assertGetFreshAddresses(poolName: String, walletName: String, index: Int, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/addresses/fresh", headers = defaultHeaders, andExpect = expected)
  }

  private def assertGetUTXO(poolName: String, walletName: String, index: Int, expected: Status): Response = {
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/utxo", headers = defaultHeaders, andExpect = expected)
    server.httpGet(s"/pools/$poolName/wallets/$walletName/accounts/$index/utxo?offset=2&batch=10", headers = defaultHeaders, andExpect = expected)
  }

  private val CORRECT_BODY_ETH =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/60'/0'",""" +
      """"extended_key": "xpub6EM1gLShjupLBK87mLsXQer5Q3VrqCXehd7e37jrQQx7aGUwMrym2mLjcmZWVuh2bscKooHvXov5D16VzFbc8ou77pnHhQ4y5m2mT5FKi2r"""" +
      """}""" +
      """]""" +
      """}"""

  private val CORRECT_BODY_XRP =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/144'/2'",""" +
      """"pub_key": "03432A07E9AE9D557F160D9B1856F909E421B399E12673EEE0F4045F4F7BA151CF",""" +
      """"chain_code": "5D958E80B0373FA505B95C1DD175B0588205D1620C56F7247B028EBCB0FB5032"""" +
      """}""" +
      """]""" +
      """}"""

  /*
    private val CORRECT_BODY_ETH2 =
      """{""" +
        """"account_index": 0,""" +
        """"derivations": [""" +
        """{""" +
        """"owner": "main",""" +
        """"path": "44'/1'/0'",""" +
        """"pub_key": "045650BE990F3CD39DF6CBAEBB8C06646727B1629509F993883681AE815EE1F3F76CC4628A600F15806D8A25AE164C061BF5EAB3A01BD8A7E8DB3BAAC07629DC67",""" +
        """"chain_code": "81F18B05DF5F54E5602A968D39AED1ED4EDC146F5971C4E84AA8273376B05D49"""" +
        """}""" +
        """]""" +
        """}"""
  */
  private val CORRECT_BODY_BITCOIN =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'/0'",""" +
      """"pub_key": "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""

  private val CORRECT_BODY_IDX1 =
    """{""" +
      """"account_index": 1,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'/1'",""" +
      """"pub_key": "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""
  private val INVALID_ARGUMENT_BODY =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "different than next owner",""" +
      """"path": "44'/0'/0'",""" +
      """"pub_key": "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""
  private val MISSING_PUBKEY_BODY =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'/0'",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""
  private val MISSING_PATH_BODY =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"pub_key": "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]""" +
      """}"""
  private val INVALID_JSON =
    """{""" +
      """"account_index": 0,""" +
      """"derivations": [""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'/0'",""" +
      """"pub_key": "0437bc83a377ea025e53eafcd18f299268d1cecae89b4f15401926a0f8b006c0f7ee1b995047b3e15959c5d10dd1563e22a2e6e4be9572aa7078e32f317677a901",""" +
      """"chain_code": "d1bb833ecd3beed6ec5f6aa79d3a424d53f5b99147b21dbc00456b05bc978a71"""" +
      """},""" +
      """{""" +
      """"owner": "main",""" +
      """"path": "44'/0'",""" +
      """"pub_key": "04fb60043afe80ee1aeb0160e2aafc94690fb4427343e8d4bf410105b1121f7a44a311668fa80a7a341554a4ef5262bc6ebd8cc981b8b600dafd40f7682edb5b3b",""" +
      """"chain_code": "88c2281acd51737c912af74cc1d1a8ba564eb7925e0d58a5500b004ba76099cb"""" +
      """}""" +
      """]"""
}
