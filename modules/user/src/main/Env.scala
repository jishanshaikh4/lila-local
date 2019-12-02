package lila.user

import akka.actor._
import com.softwaremill.macwire._
import io.methvin.play.autoconfig._
import play.api.Configuration
import play.api.libs.ws.WSClient
import scala.concurrent.duration._

import lila.common.config._
import lila.common.LightUser
import lila.db.dsl.Coll

private class UserConfig(
    @ConfigName("cached.nb.ttl") val cachedNbTtl: FiniteDuration,
    @ConfigName("online.ttl") val onlineTtl: FiniteDuration,
    @ConfigName("collection.user") val collectionUser: CollName,
    @ConfigName("collection.note") val collectionNote: CollName,
    @ConfigName("collection.trophy") val collectionTrophy: CollName,
    @ConfigName("collection.trophyKind") val collectionTrophyKind: CollName,
    @ConfigName("collection.ranking") val collectionRanking: CollName,
    @ConfigName("password.bpass.secret") val passwordBPassSecret: Secret
)

final class Env(
    appConfig: Configuration,
    db: lila.db.Env,
    mongoCache: lila.memo.MongoCache.Builder,
    asyncCache: lila.memo.AsyncCache.Builder,
    scheduler: Scheduler,
    timeline: ActorSelection,
    onlineUserIds: () => Set[User.ID]
)(implicit system: ActorSystem, ws: WSClient) {

  private val config = appConfig.get[UserConfig]("user")(AutoConfig.loader)

  val userRepo = new UserRepo(db(config.collectionUser))

  val lightUserApi: LightUserApi = wire[LightUserApi]
  val lightUser = (id: User.ID) => lightUserApi async id
  val lightUserSync = (id: User.ID) => lightUserApi sync id

  val isOnline = new IsOnline(userId => onlineUserIds() contains userId)

  lazy val jsonView = wire[JsonView]

  lazy val noteApi = {
    def mk = (coll: Coll) => wire[NoteApi]
    mk(db(config.collectionNote))
  }

  lazy val trophyApi = new TrophyApi(db(config.collectionTrophy), db(config.collectionTrophyKind))

  lazy val rankingApi = {
    def mk = (coll: Coll) => wire[RankingApi]
    mk(db(config.collectionRanking))
  }

  lazy val cached: Cached = {
    def mk = (nbTtl: FiniteDuration) => wire[Cached]
    mk(config.cachedNbTtl)
  }

  private lazy val passHasher = new PasswordHasher(
    secret = config.passwordBPassSecret,
    logRounds = 10,
    hashTimer = res => {
      lila.mon.measure(_.user.auth.hashTime) {
        lila.mon.measureIncMicros(_.user.auth.hashTimeInc)(res)
      }
    }
  )

  lazy val authenticator = wire[Authenticator]

  lazy val forms = wire[DataForm]

  system.scheduler.scheduleWithFixedDelay(1 minute, 1 minute) { () =>
    lightUserApi.monitorCache
  }

  lila.common.Bus.subscribeFuns(
    "adjustCheater" -> {
      case lila.hub.actorApi.mod.MarkCheater(userId, true) =>
        rankingApi remove userId
        userRepo.setRoles(userId, Nil)
    },
    "adjustBooster" -> {
      case lila.hub.actorApi.mod.MarkBooster(userId) => rankingApi remove userId
    },
    "kickFromRankings" -> {
      case lila.hub.actorApi.mod.KickFromRankings(userId) => rankingApi remove userId
    },
    "gdprErase" -> {
      case User.GDPRErase(user) =>
        userRepo erase user
        noteApi erase user
    }
  )
}
