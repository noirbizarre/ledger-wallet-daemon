package co.ledger.wallet.daemon.services

import java.util.Date
import java.util.concurrent.{ConcurrentHashMap, Executors, Semaphore}

import co.ledger.core.Account
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.AccountInfo
import co.ledger.wallet.daemon.models.Wallet._
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.inject.Logging
import com.twitter.util.{Duration, ScheduledThreadPoolTimer, Timer}
import javax.inject.{Inject, Singleton}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}

/**
 * This module is responsible to maintain account updated
 * It's pluggable to external trigger
 */
@Singleton
class AccountSynchronizerManager @Inject()(daemonCache: DaemonCache) extends DaemonService {

  // FIXME : ExecutionContext size
  implicit val synchronizationPool: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4 * Runtime.getRuntime.availableProcessors()))

  val scheduler = new ScheduledThreadPoolTimer(
    poolSize = 1,
    threadFactory = new NamedPoolThreadFactory("AccountSynchronizer-Scheduler")
  )

  lazy private val periodicRegisterAccount =
    scheduler.schedule(Duration.fromSeconds(600))(registerAccounts)

  def start(): Unit = {
    registerAccounts
    periodicRegisterAccount
    info("Started account synchronizer manager")
  }

  def resyncAccount(accountInfo: AccountInfo): Unit = {
    Option(map.get(accountInfo)).foreach(_.resync())
  }
  def registerAccount(account: Account, accountInfo: AccountInfo): Unit = {
    map.computeIfAbsent(accountInfo, (i: AccountInfo) => {
      info(s"registered account $i to account synchronizer manager")
      new AccountSynchronizer(account, poolName = i.poolName, walletName = i.walletName, scheduler)
    })
  }

  private val map = new ConcurrentHashMap[AccountInfo, AccountSynchronizer]()

  // return None if account info not found
  def getSyncStatus(accountInfo: AccountInfo): Option[SyncStatus] = {
    Option(map.get(accountInfo)).map(_.getSyncStatus)
  }

  // Maybe to be called periodically to discover new account
  private def registerAccounts: Future[Unit] = {
    for {
      users <- daemonCache.getUsers
      pools <- Future.sequence(users.map(u => u.pools().map(_.map(p => (u, p))))).map(_.flatten)
      wallets <- Future.sequence(pools.map { case (user, pool) => pool.wallets.map(_.map(w => (user, pool, w))) }).map(_.flatten)
      accounts <- Future.sequence(wallets.map { case (user, pool, wallet) =>
        for {
          accounts <- wallet.accounts
        } yield accounts.map(account => (user, pool, wallet, account))
      }).map(_.flatten)
    } yield {
      accounts.foreach{
        case (user, pool, wallet, account) =>
          val accountInfo = AccountInfo(
            pubKey = user.pubKey,
            walletName = wallet.getName,
            poolName = pool.name,
            accountIndex = account.getIndex
          )
          registerAccount(account, accountInfo)
      }
    }
  }

}

/**
  * AccountSynchronizer manages all synchronization task related to the account.
  * An account sync will be triggerred periodically.
  * An account can have following states:
  *                         Synced(blockHeight)                    external trigger
  *        periodic trigger ^                |    ^                      |
  *                         |                v       \                   v
  *                         Syncing(fromHeight)       Resyncing(targetHeight, currentHeight)
  * @param account
  * @param poolName
  * @param walletName
  * @param scheduler
  * @param ec the execution context for the synchronization job
  */
class AccountSynchronizer(account: Account, poolName: String, walletName: String, scheduler: Timer)
                         (implicit ec: ExecutionContext) extends Logging {
  private var syncStatus: SyncStatus = Synced(0)
  // When the account is syncing, we received a resync request, we will
  // put it in queue. There is a synchronizer reading the queue periodically
  // to start the resync
  private val resyncLatch = {
    val s = new Semaphore(1)
    s.drainPermits()
    s
  }

  // Periodically try to trigger sync. the sync will be triggered when status is Synced
  scheduler.schedule(Duration.fromSeconds(10)) {
    startPeriodicSync()
  }
  // Periodically try to update the current height in resync status.
  // do nothing if the status is not Resyncing
  scheduler.schedule(Duration.fromSeconds(3)) {
    periodicUpdateStatus()
  }
  // Periodically try to resync. It's competing with periodic sync.
  // The resync will be triggered when status is Synced and there is a resync latch
  scheduler.schedule(Duration.fromSeconds(3)) {
    tryResyncAccount()
  }


  def getSyncStatus: SyncStatus = this.synchronized(syncStatus)

  // A external control for account resync
  // the resync will be queued if status is not Resyncing
  // Won't guarantee the resync will be triggered right away, the resync request
  // will be processed by a periodic check
  def resync(): Unit = this.synchronized {
    syncStatus match {
      case Resyncing(_, _) =>
      case _ =>
        info(s"RESYNC : resync task queued for $accountInfo")
        resyncLatch.release()
    }
  }

  // This method is called periodically by `periodicSync` task
  private def startPeriodicSync(): Boolean = this.synchronized {
    syncStatus match {
      case Synced(_) | FailedToSync(_) => // do sync
        syncStatus = Syncing(lastBlockHeightSync)
        syncAccount()
        true
      case _ => // do nothing
        false
    }
  }

  // This method is called periodically by `periodicResyncStatusCheck` task
  private def periodicUpdateStatus() = this.synchronized {
    syncStatus match {
      case Resyncing(target, _) =>
        val lastHeight = lastBlockHeightSync
        syncStatus = Resyncing(target, lastHeight)
      case _ =>
    }
  }

  private def lastBlockHeightSync: Long = {
    val f = account.getLastBlock().map(_.getHeight)
    Await.result(f, 3.seconds)
  }

  // This method is called periodically by `periodicResyncCheck` task
  private def tryResyncAccount() = this.synchronized {
    if (resyncLatch.tryAcquire()) {
      info(s"RESYNC : try to resync account $accountInfo")
      syncStatus match {
        case Synced(_) | FailedToSync(_) => // do resync
          syncStatus = Resyncing(lastBlockHeightSync, 0)
          info(s"RESYNC : resyncing $accountInfo")
          for {
            _ <- account.eraseDataSince(new Date(0))
            _ = info(s"Resync : erased all the operations of $accountInfo")
            _ <- syncAccount()
          } yield ()
        case _ => // queue the resync
          info(s"RESYNC : the account $accountInfo is being syncing, postpone the resync")
          resyncLatch.release()
      }
    }
  }

  private def syncAccount() = {
    onSynchronizationStart()
    for {
      syncResult <- account.sync(poolName, walletName)
      lastBlock <- account.getLastBlock()
    } yield {
      this.synchronized {
        if (syncResult.syncResult) {
          syncStatus = Synced(lastBlock.getHeight)
        } else {
          syncStatus = FailedToSync(s"SYNC : failed to sync account $accountInfo")
        }
        onSynchronizationEnds()
      }
    }
  }

  private def onSynchronizationStart(): Unit = {
    info(s"SYNC : start syncing $accountInfo")
  }

  private def onSynchronizationEnds(): Unit = this.synchronized {
    info(s"SYNC : $accountInfo has been synced : $syncStatus")
  }

  private def accountInfo: String = {
    s"$poolName/$walletName/${account.getIndex}"
  }
}

sealed trait SyncStatus

case class Synced(atHeight: Long) extends SyncStatus

case class Syncing(fromHeight: Long) extends SyncStatus

case class FailedToSync(reason: String) extends SyncStatus

/**
 * targetHeight is the height of the most recent operation of the account before the resync.
 * currentHeight is the height of the most recent operation of the account during resyncing.
 * they serve as a progress indicator
 */
case class Resyncing(targetHeight: Long, currentHeight: Long) extends SyncStatus
