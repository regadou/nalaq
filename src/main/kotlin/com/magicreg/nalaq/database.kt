package com.magicreg.nalaq

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.charset.Charset
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass

class Database(
    private val originalUrl: String,
    override var prefix: String = ""
): Namespace {
    private val infos = extractConnectionInfos()
    private val connection = DriverManager.getConnection(infos[0], infos[1], infos[2])
    override val readOnly: Boolean = false
    override val uri: String get() = infos[0]
    val printStream: PrintStream = SqlPrintStream(this)

    override val names: List<String> get() {
        val meta = connection.metaData
        val rs = meta.getTables(null, null, null, arrayOf("TABLE"))
        val tables = mutableListOf<String>()
        while (rs.next())
            tables.add(rs.getString("TABLE_NAME").lowercase())
        rs.close()
        return tables
    }

    override fun hasName(name: String): Boolean {
        return names.contains(name)
    }

    override fun value(name: String): Any? {
        return if (name.isBlank()) this else getTable(name)
    }

    override fun setValue(name: String, value: Any?): Boolean {
        if (readOnly || name.isBlank())
            return false
        try {
            createTable(name, toMap(value) as Map<String,Any?>)
            return true
        } catch (e: Exception) {}
        return false
    }

    override fun toString(): String {
        return "Database(${infos[0]})"    
    }

    // TODO: should return a SqlTable
    fun getTable(table: String): Map<String,String>? {
        if (names.indexOf(table) < 0)
            return null
        val meta = connection.metaData
        val columns = mutableMapOf<String,String>()
        val rs = meta.getColumns(null,null,table.uppercase(),null)
        while (rs.next()) {
            val name = rs.getString("COLUMN_NAME").lowercase()
            columns[name] = getNaLaQType(rs.getObject("DATA_TYPE"), name).name
        }
        rs.close();
        return columns;
    }

    // TODO: change the signature to accept a Type or Collection<Property>
    fun createTable(table: String, data: Map<String,Any?>): Map<String,String>? {
        if (hasName(table)) // TODO: how to allow ALTER TABLE
            throw RuntimeException("Table already exists: $table")
        val columns = mutableListOf<String>()
        for (key in data.keys)
            columns.add(key+" "+getSqlType(data[key].toString()))
        val sql = "create table "+table+" (\n  "+columns.joinToString(",\n  ")+"\n)\n"
        return if (execute(sql)) getTable(table) else null
    }

    fun getPrimaryKeys(table: String): List<String> {
        val pkeys = mutableListOf<String>()
        val rs = connection.metaData.getPrimaryKeys(null, null, table.uppercase())
        while (rs.next())
            pkeys.add(rs.getString("COLUMN_NAME").lowercase())
        return pkeys
    }

    fun selectRows(table: String, filter: Filter): List<Map<String,Any?>> {
        if (!hasName(table))
            throw RuntimeException("Invalid table: $table")
        val query = buildSqlClauses(filter)
        return query("select * from $table$query")
    }

    fun insertRows(table: String, rows: List<Map<String,Any?>>): Int {
        val columns = getTable(table) ?: throw RuntimeException("Invalid table: $table")
        var count = 0
        for (row in rows) {
            val keys = mutableListOf<String>()
            val values = mutableListOf<String>()
            for (key in row.keys) {
                val type = columns[key]
                if (type != null) {
                    keys.add(key)
                    values.add(printSqlValue(row[key], type))
                }
            }
            val sql = "insert into "+table+" ("+keys.joinToString(",")+") values ("+values.joinToString(",")+")"
            count += update(sql)
        }
        return count
    }

    fun updateRows(table: String, rows: List<Map<String,Any?>>, filter: Filter): Int {
        val columns = getTable(table) ?: throw RuntimeException("Invalid table: $table")
        val query = buildSqlClauses(filter)
        var pkeys = getPrimaryKeys(table)
        var count = 0
        for (row in rows) {
            val values = mutableListOf<String>()
            for (key in row.keys) {
                if (pkeys.indexOf(key) >= 0)
                    continue
                val type = columns[key]
                if (type != null)
                    values.add(key+" = "+printSqlValue(row[key], type))
            }
            val clauses = if (query.isEmpty()) getUpdateClause(row, pkeys) else query
            val sql = "update "+table+" set "+values.joinToString(", ")+clauses
            count += update(sql)
        }
        return count
    }

    fun deleteRows(table: String, filter: Filter): Int {
        if (!hasName(table))
            throw RuntimeException("Invalid table: $table")
        val query = buildSqlClauses(filter)
        return update("delete from $table$query")
    }

    // TODO: should return List<SqlRow>
    fun query(sql: String): List<Map<String,Any?>> {
        val statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        val rs = statement.executeQuery(sql)
        val rows = mutableListOf<Map<String,Any?>>()
        while (rs.next())
            rows.add(resultToMap(rs))
        statement.close()
        return rows;
    }

    fun update(sql: String): Int {
        val statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        val result = statement.executeUpdate(sql)
        statement.close()
        return result
    }

    fun execute(sql: String): Boolean {
        val statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        val result = statement.execute(sql)
        statement.close()
        return result
    }

    private fun extractConnectionInfos(): List <String> {
        val uri = URI(originalUrl)
        if (uri.userInfo == null)
            return listOf(originalUrl, "", "")
        val userInfo = uri.userInfo
        val pindex = userInfo.indexOf(":")
        val user = if (pindex < 0) userInfo else userInfo.substring(0, pindex)
        val password = if (pindex < 0) "" else userInfo.substring(pindex+1)
        val rawUserInfo = uri.rawUserInfo
        val uindex = originalUrl.indexOf(rawUserInfo)
        val securedUrl = originalUrl.substring(0, uindex) + originalUrl.substring(uindex+rawUserInfo.length)
        return listOf(securedUrl, user, password)
    }

    // TODO: constraint should be List<DataConstraint> and should return SqlRow
    private fun resultToMap(rs: ResultSet): Map<String,Any?> {
        val map = mutableMapOf<String,Any?>()
        val meta = rs.metaData
        val nc = meta.columnCount
        for (c in 1 .. nc) {
            val name = meta.getColumnName(c)
            val type = meta.getColumnType(c)
            map[name.lowercase()] = rs.getObject(c)
        }
        return map
    }

    // TODO: should accept a Property with PropertyOption value to specify specific SQL column definition
    private fun getSqlType(def: String): String {
        return when (def) {
            "int", "date", "time", "timestamp" -> def
            "integer" -> "int"
            "number" -> "double"
            "boolean" -> "bit"
            "string" -> "varchar(255)"
            else -> if (getTypeByName(def) != null) "text" else def
        }
    }

    private fun getNaLaQType(sqlType: Any?, columnName: String): Type {
        if (sqlType == null)
            return ANY
        return when (sqlType.toString().toInt()) {
            Types.BIGINT,
            Types.INTEGER,
            Types.SMALLINT,
            Types.TINYINT -> INTEGER

            Types.DECIMAL,
            Types.DOUBLE,
            Types.FLOAT,
            Types.NUMERIC,
            Types.REAL -> DECIMAL

            Types.BIT,
            Types.BOOLEAN -> BOOLEAN

            Types.BINARY,
            Types.BLOB,
            Types.VARBINARY,
            Types.LONGVARBINARY -> BINARY

            Types.CHAR,
            Types.CLOB,
            Types.NCLOB,
            Types.NCHAR,
            Types.LONGVARCHAR,
            Types.VARCHAR -> TEXT

            Types.DATE -> DATE

            Types.TIMESTAMP,
            Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMP

            Types.TIME,
            Types.TIME_WITH_TIMEZONE -> TIME

            else -> throw RuntimeException("Column $columnName has unsupported SQL type: $sqlType")
        }
    }

    private fun getUpdateClause(item: Map<String,Any?>, keys: List<String>): String {
        val clauses = mutableListOf<String>()
        for (key in keys) {
            val value = item[key]
            clauses.add(buildSqlExpression(key, value))
        }
        return if (clauses.isEmpty()) "" else " where "+clauses.joinToString(" and ")
    }

    private fun buildSqlClauses(filter: Filter): String {
        val clauses = mutableListOf<String>()
        val iterator = filter.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val clause = mutableListOf<String>()
            for (key in item.keys) {
                val value = item[key]
                clause.add(buildSqlExpression(key, value))
            }
            if (clause.isNotEmpty())
                clauses.add(clause.joinToString(" and "))
        }
        return when (clauses.size) {
            0 -> ""
            1 -> " where "+clauses[0]
            else -> " where ("+clauses.joinToString(") or (")+")"
        }
    }

    private fun buildSqlExpression(key: String, value: Any?): String {
        if (value == null)
            return "$key is null"
        if (value is List<*>) {
            val values = mutableListOf<String>()
            for (item in value)
                values.add(printSqlValue(value))
            return key+(if (values.isEmpty()) " is not null" else " in ("+values.joinToString(", ")+")")
        }
        if (value is Array<*>)
            return buildSqlExpression(key, value.toList())
        if (value is Map<*,*>) {
            if (value.size != 1)
                throw RuntimeException("Map value in clause must have only one single operator key")
            val op = value.keys.iterator().next().toString()
            return when (op.toCompareOperator(true)!!) {
                CompareOperator.MATCH -> "$key like ${printSqlValue(value[op])}"
                CompareOperator.NOT_MATCH -> "not($key like ${printSqlValue(value[op])})"
                CompareOperator.BETWEEN -> "($key between ${printSqlRange(value[op])})"
                CompareOperator.NOT_BETWEEN -> "not($key between ${printSqlRange(value[op])})"
                CompareOperator.IN -> buildSqlExpression(key, toList(value[op]))
                CompareOperator.NOT_IN -> "not(${buildSqlExpression(key, toList(value[op]))})"
                else -> "$key $op ${printSqlValue(value[op])}"
            }
        }
        return "$key = ${printSqlValue(value)}"
    }

    private fun printSqlValue(value: Any?, type: String? = null): String {
        if (value == null)
            return "null"
        if (type == null)
            return printSqlValue(value, value.type().name)
        return when (type) {
            "int", "integer", "real", "decimal", "number" -> value.toString()
            "boolean" -> if (isTrue(value)) "1" else "0"
            "date", "time", "timestamp" -> printSqlDate(value)
            else -> "'"+toString(value).split("'").joinToString("''")+"'"
        }
    }

    private fun printSqlDate(value: Any?): String {
        if (value == null)
            return "null"
        val txt = toDateTime(value).toString().lowercase().replace('t', ' ').split(".")[0]
        return "'$txt'"
    }

    private fun printSqlRange(value: Any?): String {
        val list = if (value is List<*>) value else if (value is Array<*>) value.toList() else listOf(value)
        if (list.isEmpty())
            return "null and null"
        val first: Any? = list[0]
        val last: Any? = list[list.size-1]
        return printSqlValue(first)+" and "+printSqlValue(last)
    }

    private fun isTrue(value: Any?): Boolean {
        if (value == null)
            return false
        if (value is Number)
            return value != 0
        if (value is Array<*>)
            return value.size > 0
        if (value is List<*>)
            return value.size > 0
        if (value is Map<*,*>)
            return value.size > 0
        return value.toString().trim() != ""
    }
}

class SqlTable(
    override val name: String,
    val database: Database
): Type, Filterable {
    override val parentType: Type = getTypeByClass(Map::class)
    override val childrenTypes: List<Type> = emptyList()
    override val rootType: Type = parentType.rootType
    override val classes: List<KClass<*>> = listOf(SqlRow::class)

    override fun properties(instance: Any?): List<String> {
        return emptyList() // TODO: implement function SQLTable.properties
    }

    override fun property(name: String, instance: Any?): Property {
        return if (name.isBlank()) SelfProperty() else nullProperty() // TODO: implement function SQLTable.property
    }

    override fun newInstance(args: List<Any?>): Any? {
        return null // TODO: implement function SQLTable.newInstance
    }

    override fun isInstance(value: Any?): Boolean {
        return value is SqlRow && value.table == this
    }

    override fun filter(filter: Filter): List<Any?> {
        return database.selectRows(name, filter)
    }

    override fun toString(): String {
        return "SqlTable($name)"
    }
}

class SqlColumn(
    val table: SqlTable,
    override val name: String,
    override val type: Type,
    override val options: PropertyOptions = PropertyOptions()
): Property {
    override fun getValue(instance: Any?): Any? {
        return null // TODO: implement function SqlColumn.getValue
    }

    override fun setValue(instance: Any?, value: Any?): Boolean {
        return false // TODO: implement function SqlColumn.setValue
    }

    override fun toString(): String {
        return "SqlColumn(${table.name}/$name)"
    }
}

class SqlRow(
    val table: SqlTable,
    val id: Any?,
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
): AbstractMutableMap<String,Any?>() {
    override fun put(key: String, value: Any?): Any? {
        return null // TODO: implement function SQLRow.put
    }

    override fun toString(): String {
        return "SqlRow(${table.name}#$id)"
    }
}

class SqlRowEntry(
    override val key: String,
    private var originalValue: Any? = null
): MutableMap.MutableEntry<String, Any?> {
    override val value: Any
        get() = { originalValue }

    override fun setValue(newValue: Any?): Any? {
        val oldValue = value
        originalValue = newValue
        return value
    }

    override fun toString(): String {
        return "SqlRowEntry($key=$originalValue)"
    }
}

class SqlPrintStream(val db: Database, private val buffer: ByteArrayOutputStream = ByteArrayOutputStream()): PrintStream(buffer, true) {
    override fun flush() {
        val sql = buffer.toString(Charset.defaultCharset())
        buffer.reset()
        when (sql.substring(0,4).lowercase()) {
            "inse", "upda", "dele" -> db.update(sql)
            "crea", "drop", "alte", "trun" -> db.execute(sql)
            else -> throw RuntimeException("Unsupported SQL statement in PrintStream: $sql")
        }
    }

    override fun close() { }

    override fun write(b: Int) { print(byteArrayOf(b.toByte()).joinToString("")) }

    override fun write(buf: ByteArray?, off: Int, len: Int) {  if (buf != null) print(ByteArray(len){ buf.get(off+it) }.joinToString("")) }

    override fun write(buf: ByteArray) { print(buf.joinToString("")) }

    override fun writeBytes(buf: ByteArray) { print(buf.joinToString("")) }

    override fun print(b: Boolean) { print(b.toString()) }

    override fun print(c: Char) { print(c.toString()) }

    override fun print(i: Int) { print(i.toString()) }

    override fun print(l: Long) { print(l.toString()) }

    override fun print(f: Float) { print(f.toString()) }

    override fun print(d: Double) { print(d.toString()) }

    override fun print(s: CharArray?) { if (s != null) print(s.joinToString("")) }

    override fun print(s: String) { buffer.write(s.toByteArray(Charset.defaultCharset())) }

    override fun print(obj: Any) { print(obj.toString()) }

    override fun println() { println("") }

    override fun println(x: Boolean) { println(x.toString()) }

    override fun println(x: Char) { println(x.toString()) }

    override fun println(x: Int) { println(x.toString()) }

    override fun println(x: Long) { println(x.toString()) }

    override fun println(x: Float) { println(x.toString()) }

    override fun println(x: Double) { println(x.toString()) }

    override fun println(x: CharArray?) { println(x.toString()) }

    override fun println(x: String) {
        print("$x\n")
        flush()
    }

    override fun println(x: Any) { println(x.toString()) }
}

private val ANY = getTypeByName("any")!!
private val DECIMAL = getTypeByName("decimal")!!
private val INTEGER = getTypeByName("integer")!!
private val BOOLEAN = getTypeByName("boolean")!!
private val BINARY = getTypeByName("binary")!!
private val TEXT = getTypeByName("text")!!
private val TIMESTAMP = getTypeByName("timestamp")!!
private val DATE = getTypeByName("date")!!
private val TIME = getTypeByName("time")!!
