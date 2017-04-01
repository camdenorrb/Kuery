package com.sxtanna.database

import com.sxtanna.database.base.Database
import com.sxtanna.database.config.DatabaseConfig
import com.sxtanna.database.config.DatabaseConfigManager
import com.sxtanna.database.config.KueryConfig
import com.sxtanna.database.task.KueryTask
import com.sxtanna.database.type.SqlObject
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import kotlin.reflect.KClass

class Kuery(override val config : KueryConfig) : Database<Connection, KueryConfig, KueryTask>() {

	override val name : String = "Kuery"
	lateinit var pool : HikariDataSource
		private set

	val creators = mutableMapOf<KClass<out SqlObject>, ResultSet.() -> SqlObject>()

	override fun load() {
		val hikariConfig = HikariConfig().apply {
			jdbcUrl = "jdbc:mysql://${config.server.address}:${config.server.port}/${config.server.database}?useSSL=false"
			username = config.user.username
			password = config.user.password
			maximumPoolSize = config.pool.size
			connectionTimeout = config.pool.timeout.toLong()
			idleTimeout = config.pool.idle.toLong()
			isAutoCommit = true
		}

		pool = HikariDataSource(hikariConfig)
	}

	override fun poison() = pool.close().also { creators.clear() }


	override fun poolResource() : Connection? = pool.connection

	override fun createTask(resource : Connection) : KueryTask = KueryTask(this, resource)


	inline fun <reified T : SqlObject> addCreator(noinline creator : ResultSet.() -> T) {
		creators[T::class] = creator
	}


	companion object : DatabaseConfigManager<KueryConfig, Kuery> {

		@JvmStatic
		override fun get(file : File) : Kuery = Kuery(getConfig(file))

		@JvmStatic
		override fun getConfig(file : File) : KueryConfig {
			return DatabaseConfig.loadOrSave(file, KueryConfig.DEFAULT)
		}
	}

}