package co.ledger.wallet.daemon.modules

import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.{DaemonCache, DefaultDaemonCache}
import co.ledger.wallet.daemon.services.{AccountSynchronizerManager, UsersService}
import com.google.inject.Provides
import com.twitter.inject.{Injector, TwitterModule}
import com.twitter.util.Duration
import javax.inject.Singleton

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object DaemonCacheModule extends TwitterModule {

  @Singleton
  @Provides
  def provideDaemonCache: DaemonCache = {
    val cache = new DefaultDaemonCache()
    val t0 = System.currentTimeMillis()
    Await.result(cache.dbMigration, 1.minutes)
    info(s"Database migration end, elapsed time: ${System.currentTimeMillis() - t0} milliseconds")
    cache
  }

  override def singletonPostWarmupComplete(injector: Injector): Unit = {
    info(s"Core operation pool cpu factor is ${DaemonConfiguration.corePoolOpSizeFactor}")

    val usersService = injector.instance[UsersService](classOf[UsersService])
    DaemonConfiguration.adminUsers.map { user =>
      val existingUser = Await.result(usersService.user(user._1, user._2), 1.minutes)
      if (existingUser.isEmpty) Await.result(usersService.createUser(user._1, user._2), 1.minutes)
    }
    DaemonConfiguration.whiteListUsers.map { user =>
      val existingUser = Await.result(usersService.user(user._1), 1.minutes)
      if (existingUser.isEmpty) Await.result(usersService.createUser(user._1, user._2), 1.minutes)
    }

    if (DaemonConfiguration.updateWalletConfig) {
      Await.result(updateWalletConfig(), 5.minutes)
    }

    val accountSynchronizerManager = injector.instance[AccountSynchronizerManager](classOf[AccountSynchronizerManager])
    accountSynchronizerManager.start()
  }

  override def singletonShutdown(injector: Injector): Unit = {
    info("Shutdown Hook...")
    injector.instance[AccountSynchronizerManager](classOf[AccountSynchronizerManager]).close(Duration.fromMinutes(3))
  }

  private def updateWalletConfig(): Future[Unit] = {
    for {
      users <- provideDaemonCache.getUsers
      pools <- Future.traverse(users)(_.pools()).map(_.flatten)
      poolWallets <- Future.traverse(pools)(pool => pool.wallets.map((pool, _)))
      _ <- Future.sequence(poolWallets.flatMap { case (pool, wallets) => wallets.map(pool.updateWalletConfig) })
    } yield ()
  }
}
