package com.sxtanna.database.task

import com.sxtanna.database.Kuery
import com.sxtanna.database.ext.ALL_ROWS
import com.sxtanna.database.ext.co
import com.sxtanna.database.ext.mapWhileNext
import com.sxtanna.database.struct.Duo
import com.sxtanna.database.struct.Resolver
import com.sxtanna.database.struct.Value
import com.sxtanna.database.struct.obj.*
import com.sxtanna.database.struct.obj.Duplicate.Ignore
import com.sxtanna.database.struct.obj.Duplicate.Update
import com.sxtanna.database.struct.obj.Sort.Ascend
import com.sxtanna.database.struct.obj.Sort.Descend
import com.sxtanna.database.struct.obj.Sort.Type.Ascending
import com.sxtanna.database.struct.obj.Sort.Type.Descending
import com.sxtanna.database.struct.obj.Where.Like
import com.sxtanna.database.struct.obj.Where.Like.LikeOption
import com.sxtanna.database.struct.obj.Where.Like.LikeOption.*
import com.sxtanna.database.type.SqlObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

@Suppress("UNCHECKED_CAST")
class KueryTask(val kuery : Kuery, override val resource : Connection) : DatabaseTask<Connection>() {

	private lateinit var resultSet : ResultSet
	private lateinit var preparedStatement : PreparedStatement


	fun createTable(name : String, vararg columns : Duo<SqlType>) {
		val createStatement = "Create Table If Not Exists $name ${columns.map { "${it.name} ${it.value}" }.joinToString(", ", "(", ")")}"

		if (kuery.debug) println("Create '$createStatement'")

		preparedStatement = resource.prepareStatement(createStatement)
		preparedStatement.execute()
	}

	fun select(table : String, columns : Array<String>, where : Array<Where> = emptyArray(), order : Array<Sort> = emptyArray(), preCheck : Boolean = false, handler : ResultSet.() -> Unit) {
		var selectStatement = "Select ${columns.joinToString(", ")} From $table${if (where.isNotEmpty()) " Where ${where.map(Where::toString).joinToString(" AND ")}" else ""}"
		if (order.isNotEmpty()) selectStatement += " ORDER BY ${order.map(Sort::toString).joinToString(", ")}"

		preparedStatement = resource.prepareStatement(selectStatement)

		var offSet = 0
		where.forEachIndexed { i, where ->
			where.prepare(preparedStatement, i + 1 + offSet)
			if (where is Where.Between) offSet++
		}

		if (kuery.debug) println("Select '$selectStatement'")

		resultSet = preparedStatement.executeQuery()

		if (preCheck && resultSet.next().not()) return
		resultSet.handler()
	}

	fun insert(table : String, vararg columns : Duo<Any?>, duplicateKey : Duplicate? = null) {
		val insertStatement = "Insert Into $table (${columns.map { it.name }.joinToString()}) Values (${Array(columns.size, { "?" }).joinToString()}) ${duplicateKey?.invoke(columns[0].name) ?: ""}"

		preparedStatement = resource.prepareStatement(insertStatement)
		columns.forEachIndexed { i, value -> Value(value.value).prepare(preparedStatement, i + 1) }

		if (kuery.debug) println("Insert '$insertStatement'")

		preparedStatement.execute()
	}

	fun update(table : String, columns : Array<Duo<Any?>>, vararg where : Where = emptyArray()) {
		val updateStatement = "Update $table Set ${columns.map { "${it.name} = ?" }.joinToString()}${if (where.isNotEmpty()) " Where ${where.joinToString(" AND ")}" else ""}"

		preparedStatement = resource.prepareStatement(updateStatement)
		columns.forEachIndexed { i, value -> Value(value.value).prepare(preparedStatement, i + 1) }

		var offSet = columns.size
		where.forEachIndexed { i, where ->
			where.prepare(preparedStatement, i + 1 + offSet)
			if (where is Where.Between) offSet++
		}

		if (kuery.debug) println("Update '$updateStatement'")

		preparedStatement.executeUpdate()
	}

	fun remove(table: String, vararg where: Where = emptyArray()) {

		val removeStatement = "Delete From $table Where ${if (where.isNotEmpty()) where.joinToString(" AND ") else ""}"

		preparedStatement = resource.prepareStatement(removeStatement)

		var offSet = 0
		where.forEachIndexed { i, where ->
			where.prepare(preparedStatement, i + 1 + offSet)
			if (where is Where.Between) offSet++
		}


		if (kuery.debug) println("Delete '$removeStatement'")

		preparedStatement.executeUpdate()
	}


	fun createTable(name : String, block : CreateBuilder.() -> Unit) {
		CreateBuilder(name).apply(block).invoke()
	}

	inline fun <reified T : SqlObject> createTable(name : String = T::class.simpleName!!) {
		val columns : Array<Duo<SqlType>> = T::class.memberProperties.map { Duo(it.name, Resolver[it]) }.toTypedArray()
		createTable(name, *columns)
	}

	inline fun <reified T : SqlObject> select(table : String = T::class.simpleName!!) = SelectBuilder(T::class, table)

	inline fun <reified T : SqlObject> insert(table : String = T::class.simpleName!!) = InsertBuilder(T::class, table)

	inline fun <reified T : SqlObject> update(table : String = T::class.simpleName!!) = UpdateBuilder(T::class, table)

	inline fun <reified T : SqlObject> remove(table : String = T::class.simpleName!!) = RemoveBuilder(T::class, table)


	operator fun CreateBuilder.invoke() {
		createTable(table, *columns.toTypedArray())
	}

	operator fun <T : SqlObject> SelectBuilder<T>.invoke(handler : T.() -> Unit) {
		select(table, ALL_ROWS, where().toTypedArray(), sorts().toTypedArray()) {
			val creator = checkNotNull(kuery.creators[clazz] as? ResultSet.() -> T) { "Creator for type $clazz doesn't exist" }
			mapWhileNext(creator).forEach(handler)
		}
	}

	operator fun <T : SqlObject> InsertBuilder<T>.invoke(obj : T) {
		val columns = clazz.memberProperties.map { it.name co it.get(obj) }.toTypedArray()
		insert(table, *columns, duplicateKey = if (ignore) Ignore() else if (update.isNotEmpty()) Update(*update) else null)
	}

	operator fun <T : SqlObject> UpdateBuilder<T>.invoke(obj : T) {
		val columns = clazz.memberProperties.filterNot { it.name.toLowerCase() in ignoring }.map { it.name co it.get(obj) }.toTypedArray()
		update(table, columns, Where.Equals(primary.name, checkNotNull(primary.get(obj)) { "Primary key data cannot be null" }))
	}

	operator fun <T : SqlObject> RemoveBuilder<T>.invoke() { remove(table, *where().toTypedArray()) }


	class CreateBuilder(val table : String) {

		val columns = mutableListOf<Duo<SqlType>>()

		fun col(name : String, type : SqlType) = (name co type).apply { columns.add(this) }

		fun col(name : String, type : () -> SqlType) = (name co type()).apply { columns.add(this) }

	}

	open class SelectBuilder<T : SqlObject>(val clazz : KClass<T>, val table : String) {

		private val sorts = mutableListOf<Sort>()
		private val where = mutableListOf<Where>()


		fun sorts() : List<Sort> = sorts

		fun where() : List<Where> = where


		fun like(column : String, value : Any, option : LikeOption, not : Boolean = false) : SelectBuilder<T> {
			where.add(Like(column, value, option, not))
			return this
		}

		fun startsWith(column : String, value : Any, not : Boolean = false) = like(column, value, Starts, not)

		fun contains(column : String, value : Any, not : Boolean = false) = like(column, value, Contains, not)

		fun endWith(column : String, value : Any, not : Boolean = false) = like(column, value, Ends, not)


		fun equalTo(column : String, value : Any) : SelectBuilder<T> {
			where.add(Where.Equals(column, value))
			return this
		}

		fun between(column : String, first : Any, second : Any, not : Boolean = false) : SelectBuilder<T> {
			where.add(Where.Between(column, first, second, not))
			return this
		}

		fun lessThan(column : String, value : Any, orEqual : Boolean = false) : SelectBuilder<T> {
			where.add(Where.Less(column, value, orEqual))
			return this
		}

		fun greaterThan(column : String, value : Any, orEqual : Boolean = false) : SelectBuilder<T> {
			where.add(Where.Greater(column, value, orEqual))
			return this
		}


		fun sortedBy(vararg columns : Duo<Sort.Type>) : SelectBuilder<T> {
			columns.forEach { sorts.add(if (it.value == Ascending) Ascend(it.name) else Descend(it.name)) }
			return this
		}

		fun ascend(column : String) = sortedBy(Duo(column, Ascending))

		fun descend(column : String) = sortedBy(Duo(column, Descending))


		companion object Select {

			inline fun <reified T : SqlObject> new(table : String = T::class.simpleName!!) = SelectBuilder(T::class, table)

		}

	}

	class InsertBuilder<T : SqlObject>(val clazz : KClass<T>, val table : String) {

		var ignore = false
			private set
		var update = emptyArray<String>()
			private set


		fun onDupeIgnore() : InsertBuilder<T> {
			ignore = true
			return this
		}

		fun onDupeUpdate(vararg column : CharSequence = arrayOf(clazz.memberProperties.find { it.findAnnotation<PrimaryKey>() != null }?.name ?: "")) : InsertBuilder<T> {
			update = column.map { it.toString() }.toTypedArray()
			return this
		}


		companion object Insert {

			inline fun <reified T : SqlObject> new(table : String = T::class.simpleName!!) = InsertBuilder(T::class, table)

		}

	}

	open class UpdateBuilder<T : SqlObject>(val clazz : KClass<T>, val table : String) {

		val primary = checkNotNull(clazz.memberProperties.find { it.findAnnotation<PrimaryKey>() != null }) {
			"Cannot update object without primary key"
		}
		val ignoring = mutableSetOf<String>()

		init {
			ignoring.add(primary.name.toLowerCase())
		}


		fun ignore(vararg column : String) : UpdateBuilder<T> {
			ignoring.addAll(column.map { it.toLowerCase() })
			return this
		}

	}

	open class RemoveBuilder<T : SqlObject>(val clazz : KClass<T>, val table : String) {

		private val where = mutableListOf<Where>()


		fun where() : List<Where> = where


		fun like(column : String, value : Any, option : LikeOption, not : Boolean = false) : RemoveBuilder<T> {
			where.add(Like(column, value, option, not))
			return this
		}

		fun startsWith(column : String, value : Any, not : Boolean = false) = like(column, value, Starts, not)

		fun contains(column : String, value : Any, not : Boolean = false) = like(column, value, Contains, not)

		fun endWith(column : String, value : Any, not : Boolean = false) = like(column, value, Ends, not)


		fun equalTo(column : String, value : Any) : RemoveBuilder<T> {
			where.add(Where.Equals(column, value))
			return this
		}

		fun between(column : String, first : Any, second : Any, not : Boolean = false) : RemoveBuilder<T> {
			where.add(Where.Between(column, first, second, not))
			return this
		}

		fun lessThan(column : String, value : Any, orEqual : Boolean = false) : RemoveBuilder<T> {
			where.add(Where.Less(column, value, orEqual))
			return this
		}

		fun greaterThan(column : String, value : Any, orEqual : Boolean = false) : RemoveBuilder<T> {
			where.add(Where.Greater(column, value, orEqual))
			return this
		}

	}

}