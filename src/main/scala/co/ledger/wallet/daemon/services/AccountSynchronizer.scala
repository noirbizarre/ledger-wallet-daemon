package co.ledger.wallet.daemon.services

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ConcurrentHashMap, Executors}

import co.ledger.core.{Account, Wallet}
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.{AccountInfo, Pool}
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.util.{Duration, ScheduledThreadPoolTimer}
import javax.inject.Inject

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}

/**
  * This module is responsible to maintain account updated
  * It's pluggable to external trigger
  */
class AccountSynchronizer @Inject()(daemonCache: DaemonCache) extends DaemonService {

  // FIXME : ExecutionContext size
  implicit val synchronizationPool: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4 * Runtime.getRuntime.availableProcessors()))

  val scheduler = new ScheduledThreadPoolTimer(
    poolSize = 1,
    threadFactory = new NamedPoolThreadFactory("AccountSynchronizer-Scheduler")
  )
  // FIXME : plug config
  scheduler.schedule(Duration.fromMinutes(1), Duration.fromMinutes(1))(synchronizerTask())

  class SyncStatus(val syncFrom: AtomicLong, val syncOngoing: AtomicBoolean)

  case class PoolAccount(user: User, pool: Pool, wallet: Wallet, account: Account, height: Long)

  case class PoolAccountSyncResult(poolAccount: PoolAccount, previousHeight: Long, syncResult: SynchronizationResult)

  val accountsSyncStates = new ConcurrentHashMap[AccountInfo, SyncStatus]

  implicit def asAccountInfo(poolAccount: PoolAccount): AccountInfo =
    AccountInfo(poolAccount.account.getIndex, poolAccount.wallet.getName, poolAccount.pool.name, poolAccount.user.pubKey)

  /**
    * Synchronization task
    * List all accounts, synchronize the ones that are not syncing
    * Pluggable before and after synchronization occurs
    */
  def synchronizerTask(): Unit = {
    info("SYNC Scheduler started !")
    // Synchronize accounts
    accountsList.map {
      _.filter(a => isSyncing(a)).map(poolAccount => {
        onSynchronizationStart(poolAccount)
        poolAccount.account.sync(poolAccount.pool.name, poolAccount.wallet.getName)
          .map(result => {
            updateSyncState(poolAccount, result).map(prevHeight => {
              onSynchronizationEnds(PoolAccountSyncResult(poolAccount, prevHeight, result))
            })
          })
      })
    }

    // For each account that does not have ongoing sync :
    // Pass sync flag to true

    // Submit sync task
  }

  private def accountsList: Future[List[PoolAccount]] = {
    for {
      users <- daemonCache.getUsers
      pools <- Future.sequence(users.map(u => u.pools().map(_.map(p => (u, p))))).map(_.flatten)
      wallets <- Future.sequence(pools.map { case (user, pool) => pool.wallets.map(_.map(w => (user, pool, w))) }).map(_.flatten)
      accounts <- Future.sequence(wallets.map { case (user, pool, wallet) =>
        for {
          // Dangerous in case of failure : this value can be updated meanwhile some accounts has not been updated
          lastHeight <- wallet.lastBlockHeight
          accounts <- wallet.accounts
        } yield accounts.map(account => PoolAccount(user, pool, wallet, account, lastHeight))
      }).map(_.flatten)
    } yield accounts.toList
  }

  def isSyncing(accountInfo: AccountInfo): Boolean = accountsSyncStates.containsKey(accountInfo)

  /**
    * @param poolAccount
    * @param syncResult
    * Update states, return previous height
    */
  def updateSyncState(poolAccount: PoolAccount, syncResult: SynchronizationResult): Future[Long] =
    for {
      state <- Future.successful(accountsSyncStates.get(poolAccount))
      lastHeight <- poolAccount.wallet.lastBlockHeight
      previousHeight <- Future.successful(state.syncFrom.getAndSet(lastHeight))
    } yield previousHeight


  def onSynchronizationStart(accountInfo: AccountInfo): Unit = {
    info(s"Starting SYNC : ${accountInfo}")
  }

  def onSynchronizationEnds(result: PoolAccountSyncResult): Unit = {
    info(s"SYNC : ${result.poolAccount} has been sync : ${result.syncResult}")
  }

}
