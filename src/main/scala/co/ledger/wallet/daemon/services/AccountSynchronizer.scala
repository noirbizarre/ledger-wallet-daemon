package co.ledger.wallet.daemon.services

import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentHashMap, Executors, Semaphore}

import co.ledger.core.Account
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.exceptions.AccountNotFoundException
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.{AccountInfo, PoolInfo}
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.inject.Logging
import com.twitter.util.{Duration, ScheduledThreadPoolTimer, Timer}
import javax.inject.{Inject, Singleton}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}
import scala.util.{Success, Try}

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
    scheduler.schedule(Duration.fromSeconds(DaemonConfiguration.Synchronization.syncAccountRegisterInterval))(registerAccounts)

  private val registeredAccounts = new ConcurrentHashMap[AccountInfo, AccountSynchronizer]()

  def start(): Unit = {
    registerAccounts
    periodicRegisterAccount
    info("Started account synchronizer manager")
  }

  def resyncAccount(accountInfo: AccountInfo): Unit = {
    Option(registeredAccounts.get(accountInfo)).foreach(_.resync())
  }

  def syncAccount(accountInfo: AccountInfo): Future[SynchronizationResult] = {
    Option(registeredAccounts.get(accountInfo)).fold({
      warn(s"Trying to sync an unregistered account. $accountInfo")
      Future.failed[SynchronizationResult](AccountNotFoundException(accountInfo.accountIndex))
    })(_.eventuallyStartSync())
  }

  def syncPool(poolInfo: PoolInfo): Future[Seq[SynchronizationResult]] =
    Future.sequence(Await.result(daemonCache.withWalletPool(poolInfo)(pool =>
      for {
        wallets <- pool.wallets.map(_.map(w => (pool, w)))
        syncResults <- Future.sequence(wallets.map { case (pool, wallet) =>
          for {
            accounts <- wallet.accounts
          } yield accounts.map(account => syncAccount(AccountInfo(account.getIndex, wallet.getName, pool.name, poolInfo.pubKey)))
        }).map(_.flatten)
      } yield syncResults
    ), 1.minute))


  def registerAccount(account: Account, accountInfo: AccountInfo): Unit = {
    registeredAccounts.computeIfAbsent(accountInfo, (i: AccountInfo) => {
      info(s"registered account $i to account synchronizer manager")
      new AccountSynchronizer(account, poolName = i.poolName, walletName = i.walletName, scheduler)
    })
  }

  def unregisterAccount(accountInfo: AccountInfo): Unit = {
    registeredAccounts.computeIfPresent(accountInfo, (_: AccountInfo, as: AccountSynchronizer) => {
      as.close(Duration.fromMinutes(3))
      null
    })
  }

  // return None if account info not found
  def getSyncStatus(accountInfo: AccountInfo): Option[SyncStatus] = {
    Option(registeredAccounts.get(accountInfo)).map(_.getSyncStatus)
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
      accounts.foreach {
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

  def close(after: Duration): Unit = {
    periodicRegisterAccount.cancel()
    periodicRegisterAccount.close(after)
    val closableTasks = Future.sequence(registeredAccounts.asScala.map {
      case (accountInfo, accountSynchronizer) =>
        info(s"Closing AccountSynchronizer for account $accountInfo")
        accountSynchronizer.close(after).map(_ => info(s"AccountSynchronizer for account $accountInfo Closed"))
    })
    Await.result(closableTasks, after.inMilliseconds.millisecond)
  }
}

/**
  * AccountSynchronizer manages all synchronization task related to the account.
  * An account sync will be triggerred periodically.
  * An account can have following states:
  * Synced(blockHeight)                    external trigger
  * periodic trigger ^                |    ^                      |
  * |                v       \                   v
  * Syncing(fromHeight)       Resyncing(targetHeight, currentHeight)
  *
  * @param account
  * @param poolName
  * @param walletName
  * @param scheduler
  * @param ec the execution context for the synchronization job
  */
class AccountSynchronizer(account: Account, poolName: String, walletName: String, scheduler: Timer)
                         (implicit ec: ExecutionContext) extends Logging {
  private var syncStatus: SyncStatus = Synced(0)
  private val syncFuture: AtomicReference[Future[SynchronizationResult]] = new AtomicReference(
    Future.successful(SynchronizationResult.apply(account.getIndex, walletName, poolName, syncResult = false)))

  // When the account is syncing, we received a resync request, we will
  // put it in queue. There is a synchronizer reading the queue periodically
  // to start the resync
  private val resyncLatch = {
    val s = new Semaphore(1)
    s.drainPermits()
    s
  }

  // Periodically try to trigger sync. the sync will be triggered when status is Synced
  val periodicSyncTask = scheduler.schedule(Duration.fromSeconds(DaemonConfiguration.Synchronization.syncInterval)) {
    eventuallyStartSync()
  }

  // Periodically try to update the current height in resync status.
  // do nothing if the status is not Resyncing
  val periodicResyncStatusCheckTask = scheduler.schedule(Duration.fromSeconds(DaemonConfiguration.Synchronization.syncStatusCheckInterval)) {
    periodicUpdateStatus()
  }
  // Periodically try to resync. It's competing with periodic sync.
  // The resync will be triggered when status is Synced and there is a resync latch
  val periodicResyncCheckTask = scheduler.schedule(Duration.fromSeconds(DaemonConfiguration.Synchronization.resyncCheckInterval)) {
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

  /**
    * @param awaitOngoingSyncTimeout as ongoing sync cannot be canceled
    */
  def close(awaitOngoingSyncTimeout: Duration): Future[Unit] = {
    periodicResyncCheckTask.cancel()
    periodicResyncStatusCheckTask.cancel()
    periodicSyncTask.cancel()
    Future(Try(Await.result(syncFuture.get(), awaitOngoingSyncTimeout.inMilliseconds.millisecond))
      .fold(t => s"Failed to await for end of synchronization $accountInfo, status : $syncStatus due to error : $t",
        r => s"Successfully ended synchronization of account $accountInfo status : $syncStatus syncResult: $r"))
  }

  // This method is called periodically by `periodicSync` task
  // It can also be triggered by external command
  // Start a new sync or return the current synchronization future.
  def eventuallyStartSync(): Future[SynchronizationResult] = this.synchronized {
    syncStatus match {
      case Synced(_) | FailedToSync(_) => // do sync
        val lastHeight = lastBlockHeightSync
        syncStatus = Syncing(lastHeight, lastHeight)
        syncFuture.set(syncAccount())
      case _ => // do nothing
    }
    syncFuture.get()
  }


  // This method is called periodically by `periodicResyncStatusCheck` task
  private def periodicUpdateStatus() = this.synchronized {
    syncStatus match {
      case Resyncing(target, _) =>
        val lastHeight = lastBlockHeightSync
        syncStatus = Resyncing(target, lastHeight)
      case Syncing(fromHeight, _) =>
        val lastHeight = lastBlockHeightSync
        syncStatus = Syncing(fromHeight, lastHeight)
      case _ =>
    }
  }

  private def lastBlockHeightSync: Long = {
    val f: Future[Long] = account.firstOperation.map { o =>
      val optionLong: Option[Long] = o.map(_.getBlockHeight) // walk around for java type conversion
      optionLong.getOrElse(0L)
    }
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
          val syncTask = for {
            _ <- account.eraseDataSince(new Date(0))
            _ = info(s"Resync : erased all the operations of $accountInfo")
            syncTask <- syncAccount()
          } yield syncTask
          syncFuture.set(syncTask)
        case _ => // queue the resync
          info(s"RESYNC : the account $accountInfo is being syncing, postpone the resync")
          resyncLatch.release()
      }
    }
  }

  private def syncAccount(): Future[SynchronizationResult] = {
    this.synchronized {
      onSynchronizationStart()
      account.sync(poolName, walletName)
        .andThen {
          case Success(value) if value.syncResult => this.synchronized {
            syncStatus = Synced(lastBlockHeightSync)
            onSynchronizationEnds()
          }
          case _ => this.synchronized {
            syncStatus = FailedToSync(s"SYNC : failed to sync account $accountInfo")
            onSynchronizationEnds()
          }
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

sealed trait SyncStatus{
  def value: String
}

case class Synced(atHeight: Long) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "synced"
}

case class Syncing(fromHeight: Long, currentHeight: Long) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "syncing"
}

case class FailedToSync(reason: String) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "failed"
}

/*
  * targetHeight is the height of the most recent operation of the account before the resync.
  * currentHeight is the height of the most recent operation of the account during resyncing.
  * they serve as a progress indicator
  */
case class Resyncing(
                      @JsonProperty("sync_status_target") targetHeight: Long,
                      @JsonProperty("sync_status_current") currentHeight: Long
                    ) extends SyncStatus {
  @JsonProperty("value")
  def value: String = "resyncing"
}
