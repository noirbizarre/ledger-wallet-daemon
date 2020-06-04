package co.ledger.wallet.daemon.services

import java.util.concurrent.atomic.AtomicBoolean

import co.ledger.core.{Account, Operation}
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.exceptions.{AccountSyncException, SyncOnGoingException}
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.{PoolInfo, WalletPoolView}
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import javax.inject.{Inject, Singleton}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Try}

@Singleton
class PoolsService @Inject()(daemonCache: DaemonCache) extends DaemonService {

  import PoolsService._

  def createPool(poolInfo: PoolInfo, configuration: PoolConfiguration): Future[WalletPoolView] = {
    daemonCache.createWalletPool(poolInfo, configuration.toString).flatMap(_.view)
  }

  def pools(user: User): Future[Seq[WalletPoolView]] = {
    daemonCache.getWalletPools(user.pubKey).flatMap { pools => Future.sequence(pools.map(_.view)) }
  }

  def pool(poolInfo: PoolInfo): Future[Option[WalletPoolView]] = {
    daemonCache.getWalletPool(poolInfo).flatMap {
      case Some(pool) => pool.view.map(Option(_))
      case None => Future(None)
    }
  }

  def removePool(poolInfo: PoolInfo): Future[Unit] = {
    daemonCache.deleteWalletPool(poolInfo)
  }

  /**
    * Method to synchronize account operations from public resources. The method may take a while
    * to finish.
    *
    * We synchronize account by account in a synchronous way, to
    * avoid dead lock of lib core. The test reveal that if we make
    * parallel synchronization, the lib core will be locked.
    *
    * @return a Future of sequence of result of synchronization.
    */
  def syncOperations: Future[Seq[Try[SynchronizationResult]]] = {
    if (syncOnGoing.get()) {
      Future.failed(SyncOnGoingException())
    } else {
      syncOnGoing.set(true)
      val accountsFuture = for {
        users <- daemonCache.getUsers
        pools <- Future.sequence(users.map(_.pools())).map(_.flatten)
        wallets <- Future.sequence(pools.map { p =>
          for {
            wallets <- p.wallets
          } yield wallets.map((p.name, _))
        }).map(_.flatten)
        accounts <- Future.sequence(wallets.map { case (poolName, w) =>
          for {
            // Dangerous in case of failure : this value can be updated meanwhile some accounts has not been updated
            lastHeight <- w.lastBlockHeight
            accounts <- w.accounts
          } yield accounts.map((poolName, w, lastHeight, _))
        }).map(_.flatten)
      } yield accounts
      val resultFuture = accountsFuture.map { accounts =>
        accounts.map {
          case (poolName, wallet, lastHeight, a) =>
            info(s"SYNC : Starting for $poolName, ${wallet.getName}, $a")
            val res = for {
              syncResult <- a.sync(poolName, wallet.getName)
              // _ = info(s"SYNC : Sync ended for $poolName, ${wallet.getName}, $a, Success=${syncResult.syncResult}")
              newOperationStream <- retrieveNewOperations(a, lastHeight)
              _ <- Future.successful(info(s"SYNC : done with success ${syncResult.syncResult} $poolName, ${wallet.getName}, $a => Found ${newOperationStream.size} new operations from height= $lastHeight"))
            } yield syncResult
            Try(Await.result(res, 30.minute)).recoverWith { case t =>
              Failure(AccountSyncException(poolName, wallet.getName, a.getIndex, t))
            }
        }
      }
      resultFuture.onComplete(_ => syncOnGoing.set(false))
      resultFuture
    }
  }

  def retrieveNewOperations(account: Account, height: Long): Future[Seq[Operation]] = account.operationsFromHeight(0, 100, 1, height)

  // To avoid launching sync at the same time
  private val syncOnGoing = new AtomicBoolean(false)


  /**
    * Method to synchronize account operations from public resources. The method may take a while
    * to finish. This method only synchronize a single pool.
    *
    * @return a Future of sequence of result of synchronization.
    */
  def syncOperations(poolInfo: PoolInfo): Future[Seq[SynchronizationResult]] =
    daemonCache.withWalletPool(poolInfo)(_.sync())

}

object PoolsService {

  case class PoolConfiguration() {
    override def toString: String = ""
  }

}
