package com.yuliwen.filetran

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

data class FileCabinetEntry(val name: String, val size: Long, val modifiedAt: Long)
data class FileCabinetWriteTarget(val fileName: String, val outputStream: OutputStream)

interface FileCabinetWorkspace {
    val description: String
    fun listFiles(): List<FileCabinetEntry>
    fun openRead(fileName: String): InputStream?
    fun createWriteTarget(fileNameHint: String, mimeType: String): FileCabinetWriteTarget?
    fun deleteFile(fileName: String): Boolean
}

class DirectoryFileCabinetWorkspace(private val rootDir: File) : FileCabinetWorkspace {
    override val description: String
        get() = rootDir.absolutePath

    init {
        rootDir.mkdirs()
    }

    override fun listFiles(): List<FileCabinetEntry> {
        return rootDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile }
            .map { FileCabinetEntry(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    override fun openRead(fileName: String): InputStream? {
        val cleanName = sanitizeFileName(fileName)
        if (cleanName.isBlank()) return null
        val target = File(rootDir, cleanName)
        if (!target.exists() || !target.isFile) return null
        return runCatching { FileInputStream(target) }.getOrNull()
    }

    override fun createWriteTarget(fileNameHint: String, mimeType: String): FileCabinetWriteTarget? {
        val baseName = sanitizeFileName(fileNameHint).ifBlank { defaultFileNameForMime(mimeType) }
        val target = uniqueFile(rootDir, baseName)
        return runCatching { FileCabinetWriteTarget(target.name, target.outputStream()) }.getOrNull()
    }

    override fun deleteFile(fileName: String): Boolean {
        val cleanName = sanitizeFileName(fileName)
        if (cleanName.isBlank()) return false
        return runCatching { File(rootDir, cleanName).delete() }.getOrDefault(false)
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first

        val base = fileName.substringBeforeLast('.', fileName)
        val suffix = fileName.substringAfterLast('.', "").let {
            if (it.isBlank()) "" else ".${it}"
        }
        var index = 1
        while (true) {
            val candidate = File(dir, "${base}_${index}${suffix}")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}

class SafTreeFileCabinetWorkspace(private val context: Context, private val treeUri: Uri) : FileCabinetWorkspace {
    override val description: String
        get() = "${getRoot()?.name ?: "SAF目录"} ($treeUri)"

    override fun listFiles(): List<FileCabinetEntry> {
        val root = getRoot() ?: return emptyList()
        return root.listFiles().asSequence()
            .filter { it.isFile }
            .map { FileCabinetEntry(it.name.orEmpty(), it.length(), it.lastModified()) }
            .filter { it.name.isNotBlank() }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    override fun openRead(fileName: String): InputStream? {
        val cleanName = sanitizeFileName(fileName)
        if (cleanName.isBlank()) return null
        val doc = getRoot()?.findFile(cleanName) ?: return null
        return runCatching { context.contentResolver.openInputStream(doc.uri) }.getOrNull()
    }

    override fun createWriteTarget(fileNameHint: String, mimeType: String): FileCabinetWriteTarget? {
        val root = getRoot() ?: return null
        val baseName = sanitizeFileName(fileNameHint).ifBlank { defaultFileNameForMime(mimeType) }
        val finalName = uniqueName(root, baseName)
        val finalMime = mimeType.ifBlank { guessMimeTypeByFileName(finalName) }
        val doc = root.createFile(finalMime, finalName) ?: return null
        val output = context.contentResolver.openOutputStream(doc.uri) ?: return null
        return FileCabinetWriteTarget(finalName, output)
    }

    override fun deleteFile(fileName: String): Boolean {
        val cleanName = sanitizeFileName(fileName)
        if (cleanName.isBlank()) return false
        val doc = getRoot()?.findFile(cleanName) ?: return false
        return runCatching { doc.delete() }.getOrDefault(false)
    }

    private fun getRoot(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!root.exists() || !root.isDirectory || !root.canRead() || !root.canWrite()) return null
        return root
    }

    private fun uniqueName(root: DocumentFile, fileName: String): String {
        if (root.findFile(fileName) == null) return fileName

        val base = fileName.substringBeforeLast('.', fileName)
        val suffix = fileName.substringAfterLast('.', "").let {
            if (it.isBlank()) "" else ".${it}"
        }
        var index = 1
        while (true) {
            val candidate = "${base}_${index}${suffix}"
            if (root.findFile(candidate) == null) return candidate
            index++
        }
    }
}

private const val FILE_CODE_BOX_UPLOAD_SIZE = 4L * 1024L * 1024L * 1024L
private const val FILE_CODE_BOX_MAX_TEXT_SIZE = 222L * 1024L
private const val FILE_CODE_BOX_ADMIN_TOKEN = "FileCodeBox2023"
private const val FILE_CODE_BOX_MAX_SAVE_SECONDS = 0
private const val FILE_CODE_BOX_NAME = "文件快递柜 - FileCodeBox"
private const val FILE_CODE_BOX_DESCRIPTION = "开箱即用的文件快传系统"
private const val FILE_CODE_BOX_EXPLAIN = "请勿上传或分享违法内容。根据《中华人民共和国网络安全法》、《中华人民共和国刑法》、《中华人民共和国治安管理处罚法》等相关规定。传播或存储违法、违规内容，会受到相关处罚，严重者将承担刑事责任。本站坚决配合相关部门，确保网络内容的安全，和谐，打造绿色网络环境。"
private const val FILE_CODE_BOX_NOTIFY_TITLE = "系统通知"
private const val FILE_CODE_BOX_NOTIFY_CONTENT = "欢迎使用 FileCodeBox，本程序开源于 <a href=\"https://github.com/vastsa/FileCodeBox\" target=\"_blank\">Github</a> ，欢迎Star和Fork。"
private const val FILE_CODE_BOX_SHOW_ADMIN_ADDRESS = 0
private const val FILE_CODE_BOX_ROBOTS = "User-agent: *\nDisallow: /"
private val FILE_CODE_BOX_EXPIRE_STYLES = listOf("day", "hour", "minute", "forever", "count")
private val FILE_CODE_BOX_EXPIRE_STYLE_SET = FILE_CODE_BOX_EXPIRE_STYLES.toSet()
private val FILE_CODE_BOX_MULTI_EXPIRE_STYLES = setOf("day", "hour", "minute")
private const val FILE_CODE_BOX_MULTI_MAX_FILES = 50

private data class ParsedBody(
    val parameters: Map<String, List<String>>,
    val files: Map<String, String>,
    val rawBody: String?
)

private data class Record(
    val code: String,
    val prefix: String,
    val suffix: String,
    val uuidFileName: String?,
    val size: Long,
    val text: String?,
    val expiredAt: Long?,
    var expiredCount: Int,
    var usedCount: Int
) {
    fun name(): String = prefix + suffix
    fun isText(): Boolean = text != null
    fun fileKey(): String = uuidFileName.orEmpty()
}

private data class ExpireInfo(
    val expiredAt: Long?,
    val expiredCount: Int,
    val usedCount: Int,
    val code: String
)

private class RecordStore(private val dbFile: File) {
    private val lock = Any()
    private val records = LinkedHashMap<String, MutableList<Record>>()

    init {
        load()
    }

    fun createText(text: String, expireValue: Int, expireStyle: String): Record = synchronized(lock) {
        val expireInfo = getExpireInfo(expireValue, expireStyle)
        val record = Record(
            code = expireInfo.code,
            prefix = "Text",
            suffix = "",
            uuidFileName = null,
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            text = text,
            expiredAt = expireInfo.expiredAt,
            expiredCount = expireInfo.expiredCount,
            usedCount = expireInfo.usedCount
        )
        records[record.code] = mutableListOf(record)
        save()
        record
    }

    fun createFile(
        fileName: String,
        storedName: String,
        fileSize: Long,
        expireValue: Int,
        expireStyle: String,
        reuseCode: String? = null,
        forceTimeExpire: Boolean = false
    ): Record = synchronized(lock) {
        val cleanName = sanitizeFileName(fileName).ifBlank { "unnamed_file" }
        val prefix = cleanName.substringBeforeLast('.', cleanName)
        val suffix = cleanName.substringAfterLast('.', "").let {
            if (it.isBlank()) "" else ".${it}"
        }

        val targetCode = reuseCode.orEmpty().trim()
        val expireInfo = if (targetCode.isBlank()) {
            if (forceTimeExpire && expireStyle !in FILE_CODE_BOX_MULTI_EXPIRE_STYLES) {
                throw IllegalArgumentException("多选模式仅支持按有效期销毁（天/小时/分钟）")
            }
            getExpireInfo(expireValue, expireStyle)
        } else {
            val activeGroup = records[targetCode]?.filterNot { isExpired(it) }.orEmpty()
            if (activeGroup.isEmpty()) {
                throw IllegalArgumentException("提取码不存在或已过期，请先完成首次上传")
            }
            if (activeGroup.any { it.isText() }) {
                throw IllegalArgumentException("文本提取码不支持追加文件")
            }
            val template = activeGroup.first()
            if (template.expiredAt == null || template.expiredCount >= 0) {
                throw IllegalArgumentException("当前提取码不是按有效期销毁，无法追加文件")
            }
            if (activeGroup.size >= FILE_CODE_BOX_MULTI_MAX_FILES) {
                throw IllegalArgumentException("同一提取码最多只能上传 $FILE_CODE_BOX_MULTI_MAX_FILES 个文件")
            }
            ExpireInfo(
                expiredAt = template.expiredAt,
                expiredCount = template.expiredCount,
                usedCount = 0,
                code = targetCode
            )
        }
        val record = Record(
            code = expireInfo.code,
            prefix = prefix,
            suffix = suffix,
            uuidFileName = storedName,
            size = fileSize,
            text = null,
            expiredAt = expireInfo.expiredAt,
            expiredCount = expireInfo.expiredCount,
            usedCount = expireInfo.usedCount
        )
        val group = records.getOrPut(record.code) { mutableListOf() }
        if (group.count { !it.isText() && !isExpired(it) } >= FILE_CODE_BOX_MULTI_MAX_FILES) {
            throw IllegalArgumentException("同一提取码最多只能上传 $FILE_CODE_BOX_MULTI_MAX_FILES 个文件")
        }
        group += record
        save()
        record
    }

    fun getByCode(code: String, checkExpire: Boolean = true): Pair<Boolean, Any> = synchronized(lock) {
        val group = records[code].orEmpty()
        if (group.isEmpty()) return false to "文件不存在"
        val active = if (checkExpire) group.filterNot { isExpired(it) } else group.toList()
        if (active.isEmpty()) return false to "文件已过期"
        true to active
    }

    fun findFileByCode(code: String, fileKey: String, checkExpire: Boolean = true): Pair<Boolean, Any> = synchronized(lock) {
        val group = records[code].orEmpty()
        if (group.isEmpty()) return false to "文件不存在"
        val record = group.firstOrNull { !it.isText() && it.fileKey() == fileKey } ?: return false to "文件不存在"
        if (checkExpire && isExpired(record)) return false to "文件已过期"
        true to record
    }

    fun countActiveFilesByCode(code: String): Int = synchronized(lock) {
        records[code].orEmpty().count { !it.isText() && !isExpired(it) }
    }

    fun markUsed(record: Record) {
        synchronized(lock) {
            val group = records[record.code] ?: return@synchronized
            val target = group.firstOrNull {
                it.code == record.code &&
                    it.uuidFileName == record.uuidFileName &&
                    it.prefix == record.prefix &&
                    it.suffix == record.suffix &&
                    it.text == record.text &&
                    it.expiredAt == record.expiredAt
            } ?: return@synchronized

            target.usedCount += 1
            if (target.expiredCount > 0) {
                target.expiredCount -= 1
            }
            save()
        }
    }

    fun purgeExpired(): List<String> = synchronized(lock) {
        var changed = false
        val toDeleteFiles = LinkedHashSet<String>()
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val active = mutableListOf<Record>()
            entry.value.forEach { record ->
                if (isExpired(record)) {
                    changed = true
                    record.uuidFileName?.let(toDeleteFiles::add)
                } else {
                    active += record
                }
            }

            if (active.isEmpty()) {
                iterator.remove()
            } else if (active.size != entry.value.size) {
                entry.setValue(active)
            }
        }

        if (changed) {
            save()
        }
        toDeleteFiles.toList()
    }

    fun clearAll(): List<String> = synchronized(lock) {
        val toDeleteFiles = records.values
            .flatten()
            .mapNotNull { it.uuidFileName }
            .distinct()
        records.clear()
        save()

        val parent = dbFile.parentFile
        if (parent != null) {
            runCatching { File(parent, "${dbFile.name}.bak").delete() }
            runCatching { File(parent, "${dbFile.name}.tmp").delete() }
        }
        return toDeleteFiles
    }

    private fun isExpired(record: Record): Boolean {
        if (record.expiredCount >= 0) {
            return record.expiredCount <= 0
        }
        if (record.expiredAt == null) return false
        return record.expiredAt < System.currentTimeMillis()
    }

    private fun getExpireInfo(expireValue: Int, expireStyle: String): ExpireInfo {
        val value = expireValue.coerceAtLeast(1)
        val now = System.currentTimeMillis()

        val maxDurationMs: Long? = if (FILE_CODE_BOX_MAX_SAVE_SECONDS > 0) {
            FILE_CODE_BOX_MAX_SAVE_SECONDS * 1000L
        } else {
            null
        }

        fun checkLimit(expiredAt: Long?) {
            if (maxDurationMs != null && expiredAt != null && expiredAt - now > maxDurationMs) {
                throw IllegalArgumentException(maxSaveLimitDetail())
            }
        }

        return when (expireStyle) {
            "day" -> {
                val expiredAt = now + value * 24L * 60L * 60L * 1000L
                checkLimit(expiredAt)
                ExpireInfo(expiredAt, -1, 0, randomCode(false))
            }

            "hour" -> {
                val expiredAt = now + value * 60L * 60L * 1000L
                checkLimit(expiredAt)
                ExpireInfo(expiredAt, -1, 0, randomCode(false))
            }

            "minute" -> {
                val expiredAt = now + value * 60L * 1000L
                checkLimit(expiredAt)
                ExpireInfo(expiredAt, -1, 0, randomCode(false))
            }

            "count" -> {
                ExpireInfo(null, value, 0, randomCode(false))
            }

            "forever" -> ExpireInfo(null, -1, 0, randomCode(true))

            else -> {
                val expiredAt = now + 24L * 60L * 60L * 1000L
                checkLimit(expiredAt)
                ExpireInfo(expiredAt, -1, 0, randomCode(false))
            }
        }
    }

    private fun randomCode(stringMode: Boolean): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(5000) {
            val code = if (stringMode) {
                buildString {
                    repeat(5) {
                        append(chars[Random.nextInt(chars.length)])
                    }
                }
            } else {
                (10000 + Random.nextInt(90000)).toString()
            }
            if (!records.containsKey(code)) return code
        }
        return (System.currentTimeMillis() % 100000L).toString().padStart(5, '0')
    }

    private fun load() = synchronized(lock) {
        val backupFile = File(dbFile.parentFile, "${dbFile.name}.bak")
        val candidates = listOf(dbFile, backupFile)
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val array = runCatching { JSONArray(text) }.getOrNull() ?: continue

            records.clear()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val code = obj.optString("code").trim()
                if (code.isBlank()) continue

                val record = Record(
                    code = code,
                    prefix = obj.optString("prefix"),
                    suffix = obj.optString("suffix"),
                    uuidFileName = obj.optString("uuidFileName").takeIf { it.isNotBlank() },
                    size = obj.optLong("size", 0L),
                    text = if (obj.has("text") && !obj.isNull("text")) obj.optString("text") else null,
                    expiredAt = if (obj.has("expiredAt") && !obj.isNull("expiredAt")) obj.optLong("expiredAt") else null,
                    expiredCount = obj.optInt("expiredCount", -1),
                    usedCount = obj.optInt("usedCount", 0)
                )
                records.getOrPut(code) { mutableListOf() }.add(record)
            }
            return
        }
    }

    private fun save() = synchronized(lock) {
        val array = JSONArray()
        records.values.flatten().forEach { record ->
            array.put(
                JSONObject()
                    .put("code", record.code)
                    .put("prefix", record.prefix)
                    .put("suffix", record.suffix)
                    .put("uuidFileName", record.uuidFileName)
                    .put("size", record.size)
                    .put("text", record.text)
                    .put("expiredAt", record.expiredAt)
                    .put("expiredCount", record.expiredCount)
                    .put("usedCount", record.usedCount)
            )
        }

        runCatching {
            dbFile.parentFile?.mkdirs()
            val parent = dbFile.parentFile ?: return@runCatching
            val tempFile = File(parent, "${dbFile.name}.tmp")
            val backupFile = File(parent, "${dbFile.name}.bak")
            tempFile.writeText(array.toString(), Charsets.UTF_8)
            if (dbFile.exists()) {
                runCatching { dbFile.copyTo(backupFile, overwrite = true) }
            }
            val renamed = tempFile.renameTo(dbFile)
            if (!renamed) {
                tempFile.copyTo(dbFile, overwrite = true)
                tempFile.delete()
            }
        }
    }
}

class FileCodeBoxServer(
    private val context: Context,
    port: Int,
    private val workspace: FileCabinetWorkspace
) : NanoHTTPD(port) {

    private val store = RecordStore(File(context.filesDir, "filecodebox/records.json"))

    init {
        cleanupExpiredFiles()
    }

    override fun serve(session: IHTTPSession): Response {
        cleanupExpiredFiles()

        val path = session.uri.orEmpty()
        return when {
            session.method == Method.OPTIONS -> {
                withHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", ""))
            }

            path == "/" && session.method == Method.GET -> {
                withHeaders(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml()))
            }

            path == "/robots.txt" && session.method == Method.GET -> {
                withHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", FILE_CODE_BOX_ROBOTS))
            }

            path == "/" && session.method == Method.POST -> config()
            path == "/share/text/" && session.method == Method.POST -> shareText(session)
            path == "/share/file/" && session.method == Method.POST -> shareFile(session)
            path == "/share/select/" && session.method == Method.POST -> selectByCode(session)
            path == "/share/select/" && session.method == Method.GET -> selectByCodeGet(session)
            path == "/share/download" && session.method == Method.GET -> download(session)
            path == "/share/download/zip" && session.method == Method.GET -> downloadZip(session)
            else -> withHeaders(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found"))
        }
    }

    private fun cleanupExpiredFiles() {
        store.purgeExpired().forEach { expiredFileName ->
            workspace.deleteFile(expiredFileName)
        }
    }

    fun resetAllData(): Int {
        val targets = LinkedHashSet<String>()
        targets += store.clearAll()
        targets += workspace.listFiles().map { it.name }

        var deletedCount = 0
        targets.forEach { fileName ->
            if (workspace.deleteFile(fileName)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun config(): Response {
        val detail = JSONObject()
            .put("name", FILE_CODE_BOX_NAME)
            .put("description", FILE_CODE_BOX_DESCRIPTION)
            .put("explain", FILE_CODE_BOX_EXPLAIN)
            .put("uploadSize", FILE_CODE_BOX_UPLOAD_SIZE)
            .put("expireStyle", JSONArray(FILE_CODE_BOX_EXPIRE_STYLES))
            .put("enableChunk", 0)
            .put("openUpload", 1)
            .put("notify_title", FILE_CODE_BOX_NOTIFY_TITLE)
            .put("notify_content", FILE_CODE_BOX_NOTIFY_CONTENT)
            .put("show_admin_address", FILE_CODE_BOX_SHOW_ADMIN_ADDRESS)
            .put("max_save_seconds", FILE_CODE_BOX_MAX_SAVE_SECONDS)
        return api(detail = detail)
    }

    private fun shareText(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return api(500, detail = "PARSE_BODY_FAILED")
        val text = body.parameters["text"]?.firstOrNull().orEmpty()
        val expireValue = body.parameters["expire_value"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val expireStyle = body.parameters["expire_style"]?.firstOrNull().orEmpty().ifBlank { "day" }
        if (expireStyle !in FILE_CODE_BOX_EXPIRE_STYLE_SET) {
            return api(400, detail = "过期时间类型错误")
        }

        if (text.toByteArray(Charsets.UTF_8).size.toLong() > FILE_CODE_BOX_MAX_TEXT_SIZE) {
            return api(403, detail = "内容过多,建议采用文件形式")
        }

        val record = runCatching {
            store.createText(text, expireValue, expireStyle)
        }.getOrElse { error ->
            return api(403, detail = error.message ?: "参数错误")
        }

        return api(detail = JSONObject().put("code", record.code))
    }

    private fun shareFile(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return api(500, detail = "PARSE_BODY_FAILED")

        val expireValue = body.parameters["expire_value"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val expireStyle = body.parameters["expire_style"]?.firstOrNull().orEmpty().ifBlank { "day" }
        if (expireStyle !in FILE_CODE_BOX_EXPIRE_STYLE_SET) {
            return api(400, detail = "过期时间类型错误")
        }
        val multiModeRaw = body.parameters["multi_mode"]?.firstOrNull().orEmpty()
        val multiMode = multiModeRaw == "1" || multiModeRaw.equals("true", ignoreCase = true)
        if (multiMode && expireStyle !in FILE_CODE_BOX_MULTI_EXPIRE_STYLES) {
            return api(400, detail = "多选模式仅支持按有效期销毁（天/小时/分钟）")
        }
        val groupCode = body.parameters["group_code"]?.firstOrNull().orEmpty().trim()

        val tempPath = body.files["file"] ?: body.files.entries.firstOrNull { it.key != "postData" }?.value
            ?: return api(400, detail = "未检测到上传文件")

        val tempFile = File(tempPath)
        if (!tempFile.exists() || !tempFile.isFile) {
            return api(400, detail = "上传文件无效")
        }

        val fileSize = tempFile.length()
        if (fileSize > FILE_CODE_BOX_UPLOAD_SIZE) {
            val maxMb = FILE_CODE_BOX_UPLOAD_SIZE.toDouble() / (1024.0 * 1024.0)
            return api(403, detail = "大小超过限制,最大为${String.format(Locale.US, "%.2f", maxMb)} MB")
        }

        val sourceName = body.parameters["file"]?.firstOrNull().orEmpty()
        val originalName = sanitizeFileName(sourceName).ifBlank {
            sanitizeFileName(tempFile.name).ifBlank { "upload_${System.currentTimeMillis()}.bin" }
        }
        val mimeType = guessMimeTypeByFileName(originalName)

        val writeTarget = workspace.createWriteTarget(originalName, mimeType)
            ?: return api(500, detail = "创建目标文件失败")

        val saved = runCatching {
            tempFile.inputStream().use { input ->
                writeTarget.outputStream.use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        }.isSuccess

        if (!saved) {
            workspace.deleteFile(writeTarget.fileName)
            return api(500, detail = "文件保存失败")
        }

        val record = runCatching {
            store.createFile(
                fileName = originalName,
                storedName = writeTarget.fileName,
                fileSize = fileSize,
                expireValue = expireValue,
                expireStyle = expireStyle,
                reuseCode = groupCode.takeIf { it.isNotBlank() },
                forceTimeExpire = multiMode
            )
        }.getOrElse { error ->
            workspace.deleteFile(writeTarget.fileName)
            return api(403, detail = error.message ?: "参数错误")
        }

        val detail = JSONObject()
            .put("code", record.code)
            .put("name", originalName)
            .put("reused", groupCode.isNotBlank())
            .put("file_count", store.countActiveFilesByCode(record.code))
        return api(detail = detail)
    }

    private fun selectByCode(session: IHTTPSession): Response {
        val body = parseBody(session) ?: return api(500, detail = "PARSE_BODY_FAILED")
        val code = extractCode(body)
        if (code.isBlank()) return api(400, detail = "EMPTY_CODE")

        val (exists, data) = store.getByCode(code)
        if (!exists) return api(404, detail = data)

        val records = (data as? List<*>)?.filterIsInstance<Record>().orEmpty()
        if (records.isEmpty()) return api(404, detail = "文件不存在")

        val textRecord = records.firstOrNull { it.isText() }
        if (textRecord != null && records.size == 1) {
            store.markUsed(textRecord)
            val detail = JSONObject()
                .put("code", textRecord.code)
                .put("name", textRecord.name())
                .put("size", textRecord.size)
                .put("text", textRecord.text.orEmpty())
                .put("file_type", "text")
                .put("download_url", JSONObject.NULL)
                .put("files", JSONArray())
                .put("file_count", 0)
                .put("package_download_url", JSONObject.NULL)
            appendDestroyInfo(detail, textRecord)
            return api(detail = detail)
        }

        val fileRecords = records.filter { !it.isText() && it.fileKey().isNotBlank() }
        if (fileRecords.isEmpty()) {
            return api(404, detail = "文件已过期删除")
        }

        val files = JSONArray()
        var totalSize = 0L
        fileRecords.forEach { record ->
            totalSize += record.size.coerceAtLeast(0L)
            val fileKey = record.fileKey()
            val downloadUrl = "/share/download?key=${buildFileToken(record.code, fileKey)}&code=${urlEncode(record.code)}&file=${urlEncode(fileKey)}"
            val item = JSONObject()
                .put("id", fileKey)
                .put("name", record.name())
                .put("size", record.size)
                .put("download_url", downloadUrl)
            appendDestroyInfo(item, record)
            files.put(item)
        }

        val firstFile = fileRecords.first()
        val firstDownloadUrl = "/share/download?key=${buildFileToken(firstFile.code, firstFile.fileKey())}&code=${urlEncode(firstFile.code)}&file=${urlEncode(firstFile.fileKey())}"
        val packageDownloadUrl = "/share/download/zip?key=${buildZipToken(code)}&code=${urlEncode(code)}"

        val detail = JSONObject()
            .put("code", code)
            .put("name", if (fileRecords.size == 1) firstFile.name() else "共 ${fileRecords.size} 个文件")
            .put("size", totalSize)
            .put("text", if (fileRecords.size == 1) firstDownloadUrl else JSONObject.NULL)
            .put("file_type", if (fileRecords.size == 1) "file" else "multi_file")
            .put("download_url", if (fileRecords.size == 1) firstDownloadUrl else JSONObject.NULL)
            .put("files", files)
            .put("file_count", fileRecords.size)
            .put("package_download_url", packageDownloadUrl)

        appendDestroyInfo(detail, firstFile)

        return api(detail = detail)
    }

    private fun appendDestroyInfo(detail: JSONObject, record: Record) {
        val now = System.currentTimeMillis()
        val expiredAt = record.expiredAt
        val remainingSeconds = if (expiredAt != null) {
            ((expiredAt - now).coerceAtLeast(0L)) / 1000L
        } else {
            -1L
        }

        val destroyMode = when {
            record.expiredCount >= 0 -> "count"
            expiredAt == null -> "forever"
            else -> "time"
        }

        val destroyInfo = when (destroyMode) {
            "forever" -> "永久有效"
            "count" -> {
                if (record.expiredCount > 0) {
                    "剩余下载次数 ${record.expiredCount} 次"
                } else {
                    "下载次数已用尽，当前为最后一次"
                }
            }
            else -> "剩余有效期 ${formatDuration(remainingSeconds)}"
        }

        detail.put("destroy_mode", destroyMode)
        detail.put("destroy_info", destroyInfo)
        detail.put("remaining_seconds", if (remainingSeconds >= 0) remainingSeconds else JSONObject.NULL)
        detail.put("remaining_count", if (record.expiredCount >= 0) record.expiredCount else JSONObject.NULL)
        detail.put("destroy_at", expiredAt ?: JSONObject.NULL)
        detail.put("destroy_at_text", expiredAt?.let { formatDateTime(it) } ?: JSONObject.NULL)
    }

    private fun selectByCodeGet(session: IHTTPSession): Response {
        val code = session.parameters["code"]?.firstOrNull().orEmpty().trim()
        if (code.isBlank()) return api(400, detail = "EMPTY_CODE")

        val (exists, data) = store.getByCode(code)
        if (!exists) return api(404, detail = data)

        val records = (data as? List<*>)?.filterIsInstance<Record>().orEmpty()
        if (records.isEmpty()) return api(404, detail = "文件不存在")

        val textRecord = records.firstOrNull { it.isText() }
        if (textRecord != null && records.size == 1) {
            store.markUsed(textRecord)
            return withHeaders(newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", textRecord.text.orEmpty()))
        }

        val fileRecords = records.filter { !it.isText() }
        if (fileRecords.size != 1) {
            return api(400, detail = "当前提取码包含多个文件，请在页面中选择单独下载或打包下载")
        }

        return streamFile(fileRecords.first())
    }

    private fun download(session: IHTTPSession): Response {
        val key = session.parameters["key"]?.firstOrNull().orEmpty().trim()
        val code = session.parameters["code"]?.firstOrNull().orEmpty().trim()
        val fileKey = session.parameters["file"]?.firstOrNull().orEmpty().trim()

        if (key.isBlank() || code.isBlank()) {
            return api(400, detail = "参数缺失")
        }

        if (fileKey.isBlank()) {
            if (buildSelectToken(code) != key) {
                return withHeaders(newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json; charset=utf-8", JSONObject().put("detail", "下载鉴权失败").toString()))
            }

            val (exists, data) = store.getByCode(code, checkExpire = true)
            if (!exists) return api(404, detail = "文件不存在")

            val records = (data as? List<*>)?.filterIsInstance<Record>().orEmpty()
            if (records.isEmpty()) return api(404, detail = "文件不存在")

            val textRecord = records.firstOrNull { it.isText() }
            if (textRecord != null && records.size == 1) {
                store.markUsed(textRecord)
                return api(detail = textRecord.text.orEmpty())
            }

            val fileRecords = records.filter { !it.isText() }
            if (fileRecords.size != 1) {
                return api(400, detail = "该提取码包含多个文件，请使用文件列表中的下载链接")
            }
            return streamFile(fileRecords.first())
        }

        val allowLegacyToken = buildSelectToken(code) == key
        if (!allowLegacyToken && buildFileToken(code, fileKey) != key) {
            return withHeaders(newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json; charset=utf-8", JSONObject().put("detail", "下载鉴权失败").toString()))
        }

        val (exists, data) = store.findFileByCode(code, fileKey, checkExpire = true)
        if (!exists) return api(404, detail = data)
        val record = data as Record
        return streamFile(record)
    }

    private fun downloadZip(session: IHTTPSession): Response {
        val key = session.parameters["key"]?.firstOrNull().orEmpty().trim()
        val code = session.parameters["code"]?.firstOrNull().orEmpty().trim()
        if (key.isBlank() || code.isBlank()) {
            return api(400, detail = "参数缺失")
        }
        if (buildZipToken(code) != key) {
            return withHeaders(newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json; charset=utf-8", JSONObject().put("detail", "下载鉴权失败").toString()))
        }

        val (exists, data) = store.getByCode(code, checkExpire = true)
        if (!exists) return api(404, detail = data)
        val records = (data as? List<*>)?.filterIsInstance<Record>().orEmpty()
        val fileRecords = records.filter { !it.isText() && it.fileKey().isNotBlank() }
        if (fileRecords.isEmpty()) {
            return api(404, detail = "文件已过期删除")
        }

        val input = buildZipInputStream(fileRecords)
        val zipName = "filecodebox_${sanitizeFileName(code)}.zip"
        fileRecords.forEach { store.markUsed(it) }
        val response = newChunkedResponse(Response.Status.OK, "application/zip", input)
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${urlEncode(zipName)}")
        response.addHeader("Accept-Ranges", "bytes")
        return withHeaders(response)
    }

    private fun streamFile(record: Record): Response {
        val input = workspace.openRead(record.uuidFileName.orEmpty()) ?: return api(404, detail = "文件已过期删除")
        store.markUsed(record)
        val response = newChunkedResponse(Response.Status.OK, guessMimeTypeByFileName(record.name()), input)
        response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''${urlEncode(record.name())}")
        response.addHeader("Accept-Ranges", "bytes")
        return withHeaders(response)
    }

    private fun buildZipInputStream(records: List<Record>): InputStream {
        val zipInput = PipedInputStream(64 * 1024)
        val zipOutput = PipedOutputStream(zipInput)
        Thread {
            runCatching {
                ZipOutputStream(BufferedOutputStream(zipOutput)).use { zip ->
                    val usedNames = LinkedHashSet<String>()
                    records.forEachIndexed { index, record ->
                        val source = workspace.openRead(record.uuidFileName.orEmpty()) ?: return@forEachIndexed
                        val baseName = sanitizeZipEntryName(record.name().ifBlank { "file_${index + 1}" })
                        val entryName = uniqueZipEntryName(baseName, usedNames)
                        source.use { input ->
                            zip.putNextEntry(ZipEntry(entryName))
                            input.copyTo(zip, 64 * 1024)
                            zip.closeEntry()
                        }
                    }
                }
            }.onFailure {
                runCatching { zipOutput.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return zipInput
    }

    private fun parseBody(session: IHTTPSession): ParsedBody? {
        return runCatching {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val rawBody = files["postData"]?.let { path ->
                runCatching { File(path).readText(Charsets.UTF_8) }.getOrNull()
            }
            ParsedBody(session.parameters, files, rawBody)
        }.getOrNull()
    }

    private fun extractCode(body: ParsedBody): String {
        val formCode = body.parameters["code"]?.firstOrNull().orEmpty().trim()
        if (formCode.isNotBlank()) return formCode

        val raw = body.rawBody?.trim().orEmpty()
        if (raw.isBlank()) return ""

        if (raw.startsWith("{") && raw.endsWith("}")) {
            val jsonCode = runCatching { JSONObject(raw).optString("code").trim() }.getOrDefault("")
            if (jsonCode.isNotBlank()) return jsonCode
        }

        val formMap = parseFormEncoded(raw)
        return formMap["code"].orEmpty().trim()
    }

    private fun parseFormEncoded(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val index = pair.indexOf('=')
            if (index <= 0) return@forEach
            val key = pair.substring(0, index)
            val value = pair.substring(index + 1)
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrElse { key }
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { value }
            if (decodedKey.isNotBlank()) {
                result[decodedKey] = decodedValue
            }
        }
        return result
    }

    private fun api(code: Int = 200, message: String = "ok", detail: Any? = null): Response {
        val payload = JSONObject()
            .put("code", code)
            .put("message", message)
            .put("detail", detail)
            .toString()

        val status = when (code) {
            in 200..299 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return withHeaders(newFixedLengthResponse(status, "application/json; charset=utf-8", payload))
    }

    private fun withHeaders(response: Response): Response {
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    private fun pageHtml(): String {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>$FILE_CODE_BOX_NAME</title>
              <style>
                :root {
                  --bg: #f3f6ff;
                  --card: #ffffff;
                  --text: #1f2937;
                  --muted: #6b7280;
                  --line: #d6dce8;
                  --primary: #2563eb;
                  --primary-dark: #1d4ed8;
                  --danger-bg: #fee2e2;
                  --danger-text: #991b1b;
                  --ok-bg: #dcfce7;
                  --ok-text: #166534;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                  background: radial-gradient(1200px 500px at 100% -5%, #dbeafe 0%, rgba(219,234,254,0) 65%), var(--bg);
                  color: var(--text);
                }
                .wrap { max-width: 900px; margin: 0 auto; padding: 18px 16px 32px; }
                .header {
                  background: linear-gradient(130deg, #eff6ff 0%, #ffffff 60%);
                  border: 1px solid var(--line);
                  border-radius: 16px;
                  padding: 16px;
                  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
                }
                h1 { margin: 0; font-size: 24px; }
                p { margin: 8px 0 0; color: var(--muted); }
                .nav {
                  display: flex;
                  gap: 10px;
                  margin-top: 14px;
                }
                .nav button {
                  flex: 1;
                  border: 1px solid var(--line);
                  background: #ffffff;
                  color: var(--text);
                  border-radius: 12px;
                  padding: 10px 12px;
                  cursor: pointer;
                  font-weight: 600;
                }
                .nav button.active {
                  border-color: var(--primary);
                  background: var(--primary);
                  color: #ffffff;
                }
                .status {
                  margin-top: 12px;
                  border-radius: 10px;
                  padding: 10px 12px;
                  display: none;
                  font-size: 14px;
                }
                .status.ok { display: block; background: var(--ok-bg); color: var(--ok-text); }
                .status.error { display: block; background: var(--danger-bg); color: var(--danger-text); }
                .card {
                  margin-top: 12px;
                  background: var(--card);
                  border: 1px solid var(--line);
                  border-radius: 14px;
                  padding: 14px;
                  box-shadow: 0 8px 20px rgba(30, 41, 59, 0.07);
                }
                .card-title {
                  margin: 0 0 10px;
                  font-size: 16px;
                  font-weight: 700;
                }
                .file-input-native {
                  position: absolute;
                  width: 1px;
                  height: 1px;
                  opacity: 0;
                  overflow: hidden;
                  pointer-events: none;
                }
                .file-picker {
                  display: flex;
                  align-items: center;
                  gap: 10px;
                  margin-top: 8px;
                }
                .file-picker-btn {
                  width: auto;
                  min-width: 132px;
                  border-radius: 10px;
                  border: 1px solid #93c5fd;
                  background: linear-gradient(140deg, #e0ecff 0%, #f8fbff 100%);
                  color: #1d4ed8;
                  font-weight: 700;
                  font-size: 14px;
                  text-align: center;
                  padding: 10px 12px;
                  cursor: pointer;
                  user-select: none;
                }
                .file-picker-btn:hover {
                  border-color: #60a5fa;
                  background: linear-gradient(140deg, #dbeafe 0%, #eff6ff 100%);
                }
                .file-picker-name {
                  flex: 1;
                  min-height: 42px;
                  border-radius: 10px;
                  border: 1px dashed #bfdbfe;
                  background: #f8fbff;
                  color: #475569;
                  font-size: 13px;
                  line-height: 20px;
                  padding: 10px 12px;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                }
                input, textarea, button {
                  width: 100%;
                  margin-top: 8px;
                  font-size: 14px;
                  border-radius: 10px;
                }
                input, textarea {
                  border: 1px solid var(--line);
                  background: #fff;
                  color: var(--text);
                  padding: 10px 12px;
                }
                textarea {
                  min-height: 110px;
                  resize: vertical;
                }
                button.action {
                  border: 0;
                  padding: 10px 12px;
                  color: #fff;
                  background: var(--primary);
                  cursor: pointer;
                  font-weight: 600;
                }
                button.action:hover { background: var(--primary-dark); }
                button.secondary {
                  border: 1px solid var(--line);
                  color: #374151;
                  background: #fff;
                  cursor: pointer;
                }
                .row { display: flex; gap: 10px; flex-wrap: wrap; }
                .row > * { flex: 1 1 180px; }
                .tip { margin-top: 8px; color: var(--muted); font-size: 12px; line-height: 1.5; }
                .hidden { display: none; }
                .subnav {
                  display: flex;
                  gap: 8px;
                  margin-top: 12px;
                }
                .subnav button {
                  flex: 1;
                  border: 1px solid var(--line);
                  background: #ffffff;
                  color: var(--text);
                  border-radius: 10px;
                  padding: 8px 10px;
                  font-weight: 600;
                  cursor: pointer;
                }
                .subnav button.active {
                  border-color: var(--primary);
                  background: #eff6ff;
                  color: #1d4ed8;
                }
                .field { margin-top: 8px; }
                .field label {
                  display: block;
                  margin-bottom: 4px;
                  color: #475569;
                  font-size: 13px;
                  font-weight: 600;
                }
                select {
                  width: 100%;
                  margin-top: 0;
                  font-size: 14px;
                  border-radius: 10px;
                  border: 1px solid var(--line);
                  background: #fff;
                  color: var(--text);
                  padding: 10px 12px;
                }
                .progress-wrap {
                  margin-top: 10px;
                  border: 1px solid #bfdbfe;
                  border-radius: 10px;
                  background: #eff6ff;
                  padding: 10px;
                }
                .progress-header {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  color: #1d4ed8;
                  font-size: 13px;
                  font-weight: 600;
                }
                .progress-track {
                  margin-top: 8px;
                  width: 100%;
                  height: 10px;
                  border-radius: 999px;
                  overflow: hidden;
                  background: #dbeafe;
                }
                .progress-bar {
                  height: 100%;
                  width: 0%;
                  background: linear-gradient(90deg, #2563eb 0%, #60a5fa 100%);
                  transition: width .2s ease;
                }
                .result-box {
                  margin-top: 10px;
                  border: 1px dashed #93c5fd;
                  background: #eff6ff;
                  border-radius: 10px;
                  padding: 10px;
                  font-size: 14px;
                  line-height: 1.6;
                  word-break: break-word;
                }
                .queue-item {
                  border: 1px solid #cbd5e1;
                  background: #f8fafc;
                  border-radius: 8px;
                  padding: 8px 10px;
                  margin-top: 8px;
                }
                .queue-item-main {
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  gap: 8px;
                  font-size: 13px;
                }
                .queue-actions {
                  display: flex;
                  gap: 8px;
                }
                .queue-actions button {
                  margin-top: 0;
                  width: auto;
                  padding: 6px 10px;
                  font-size: 12px;
                }
                .queue-status {
                  margin-top: 4px;
                  font-size: 12px;
                  color: #475569;
                }
                .download-list {
                  margin-top: 8px;
                }
                .download-item {
                  border: 1px solid #cbd5e1;
                  border-radius: 8px;
                  background: #f8fafc;
                  padding: 8px 10px;
                  margin-top: 8px;
                }
                .download-item .row {
                  margin-top: 8px;
                }
                .modal-mask {
                  position: fixed;
                  inset: 0;
                  background: rgba(15, 23, 42, 0.45);
                  display: none;
                  align-items: center;
                  justify-content: center;
                  padding: 16px;
                  z-index: 999;
                }
                .modal {
                  width: 100%;
                  max-width: 420px;
                  background: #fff;
                  border-radius: 16px;
                  border: 1px solid var(--line);
                  padding: 16px;
                  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.24);
                }
                .modal h3 { margin: 0 0 8px; font-size: 20px; }
                .code-pill {
                  margin-top: 8px;
                  border: 1px solid #93c5fd;
                  background: #eff6ff;
                  border-radius: 12px;
                  text-align: center;
                  font-size: 30px;
                  font-weight: 800;
                  letter-spacing: 2px;
                  padding: 10px 6px;
                  color: #1e3a8a;
                }
                .modal .row { margin-top: 10px; }
                @media (max-width: 640px) {
                  .wrap { padding: 14px 12px 26px; }
                  h1 { font-size: 22px; }
                }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="header">
                  <h1>$FILE_CODE_BOX_NAME</h1>
                  <p>$FILE_CODE_BOX_DESCRIPTION</p>
                  <div class="tip">像拿快递一样取文件：上传成功后会弹出提取码，切换到提取页输入提取码即可获取文件或文本。</div>
                  <div class="nav">
                    <button id="navUpload" class="active" type="button">上传页</button>
                    <button id="navPickup" type="button">提取页</button>
                  </div>
                </div>

                <div id="statusBar" class="status"></div>

                <section id="uploadPage">
                  <div class="subnav">
                    <button id="subNavFile" class="active" type="button">文件上传</button>
                    <button id="subNavText" type="button">文本分享</button>
                  </div>

                  <div id="uploadFilePanel" class="card">
                    <div class="card-title">上传文件</div>
                    <input id="fileInput" class="file-input-native" type="file" multiple />
                    <div class="file-picker">
                      <label for="fileInput" class="file-picker-btn">添加文件</label>
                      <div id="fileNameText" class="file-picker-name">未选择文件，可重复添加</div>
                    </div>
                    <div class="row">
                      <div class="field">
                        <label for="fileExpireStyle">销毁方式</label>
                        <select id="fileExpireStyle">
                          <option value="day">按天数销毁</option>
                          <option value="hour">按小时销毁</option>
                          <option value="minute">按分钟销毁</option>
                        </select>
                      </div>
                      <div id="fileExpireValueWrap" class="field">
                        <label id="fileExpireValueLabel" for="fileExpireValue">保存天数</label>
                        <input id="fileExpireValue" type="number" min="1" value="1" />
                      </div>
                    </div>
                    <div id="fileQueueTip" class="tip">已选择 0 / 50 个文件，可继续从其他目录添加。</div>
                    <div id="fileQueueList" class="result-box">当前没有待上传文件。</div>
                    <div id="uploadProgressWrap" class="progress-wrap hidden">
                      <div class="progress-header">
                        <span>上传进度</span>
                        <span id="uploadProgressText">0%</span>
                      </div>
                      <div class="progress-track">
                        <div id="uploadProgressBar" class="progress-bar"></div>
                      </div>
                    </div>
                    <div class="tip">单文件最大支持 4GB；同一码最多 50 个文件；最多同时上传 3 个文件。</div>
                    <div class="row">
                      <button class="secondary" type="button" onclick="clearFileQueue()">清空列表</button>
                      <button id="uploadStartBtn" class="action" type="button" onclick="uploadFileBatch()">开始上传</button>
                    </div>
                  </div>

                  <div id="uploadTextPanel" class="card hidden">
                    <div class="card-title">分享文本</div>
                    <textarea id="textInput" placeholder="输入要分享的文本"></textarea>
                    <div class="row">
                      <div class="field">
                        <label for="textExpireStyle">销毁方式</label>
                        <select id="textExpireStyle">
                          <option value="day">按天数销毁</option>
                          <option value="hour">按小时销毁</option>
                          <option value="minute">按分钟销毁</option>
                          <option value="count">按下载次数销毁</option>
                          <option value="forever">永久有效</option>
                        </select>
                      </div>
                      <div id="textExpireValueWrap" class="field">
                        <label id="textExpireValueLabel" for="textExpireValue">保存天数</label>
                        <input id="textExpireValue" type="number" min="1" value="1" />
                      </div>
                    </div>
                    <button class="action" type="button" onclick="shareText()">提交文本并生成提取码</button>
                  </div>
                </section>

                <section id="pickupPage" class="hidden">
                  <div class="card">
                    <div class="card-title">使用提取码下载/查看</div>
                    <input id="codeInput" maxlength="16" placeholder="输入提取码，例如 12345" />
                    <button class="action" type="button" onclick="queryCode()">查询内容</button>
                    <div class="tip">输入提取码后可查看当前全部可下载文件，支持逐个下载或一键打包 ZIP。</div>
                  </div>

                  <div id="resultCard" class="card hidden">
                    <div class="card-title">提取结果</div>
                    <div id="resultMeta" class="tip"></div>
                    <div id="resultBox" class="result-box"></div>
                  </div>
                </section>
              </div>

              <div id="codeModalMask" class="modal-mask">
                <div class="modal">
                  <h3>提取码已生成</h3>
                  <div class="tip">请保存此提取码，接收方可在提取页使用。</div>
                  <div id="codeValue" class="code-pill">-----</div>
                  <div class="row">
                    <button class="action" type="button" onclick="copyCode()">复制提取码</button>
                    <button class="secondary" type="button" onclick="goPickupPage()">去提取页</button>
                  </div>
                  <button class="secondary" type="button" onclick="hideCodeModal()">关闭</button>
                </div>
              </div>

              <script>
                const navUpload = document.getElementById('navUpload');
                const navPickup = document.getElementById('navPickup');
                const uploadPage = document.getElementById('uploadPage');
                const pickupPage = document.getElementById('pickupPage');
                const subNavFile = document.getElementById('subNavFile');
                const subNavText = document.getElementById('subNavText');
                const uploadFilePanel = document.getElementById('uploadFilePanel');
                const uploadTextPanel = document.getElementById('uploadTextPanel');
                const statusBar = document.getElementById('statusBar');
                const resultCard = document.getElementById('resultCard');
                const resultMeta = document.getElementById('resultMeta');
                const resultBox = document.getElementById('resultBox');
                const codeInput = document.getElementById('codeInput');
                const fileInput = document.getElementById('fileInput');
                const fileNameText = document.getElementById('fileNameText');
                const fileQueueTip = document.getElementById('fileQueueTip');
                const fileQueueList = document.getElementById('fileQueueList');
                const uploadStartBtn = document.getElementById('uploadStartBtn');
                const fileExpireStyle = document.getElementById('fileExpireStyle');
                const fileExpireValueWrap = document.getElementById('fileExpireValueWrap');
                const fileExpireValueLabel = document.getElementById('fileExpireValueLabel');
                const fileExpireValue = document.getElementById('fileExpireValue');
                const textExpireStyle = document.getElementById('textExpireStyle');
                const textExpireValueWrap = document.getElementById('textExpireValueWrap');
                const textExpireValueLabel = document.getElementById('textExpireValueLabel');
                const textExpireValue = document.getElementById('textExpireValue');
                const uploadProgressWrap = document.getElementById('uploadProgressWrap');
                const uploadProgressText = document.getElementById('uploadProgressText');
                const uploadProgressBar = document.getElementById('uploadProgressBar');
                const codeModalMask = document.getElementById('codeModalMask');
                const codeValue = document.getElementById('codeValue');
                const MAX_MULTI_FILES = 50;
                const MAX_PARALLEL_UPLOADS = 3;
                let currentCode = '';
                let pendingDownloadUrl = '';
                let uploadRunning = false;
                let fileQueue = [];
                let queueIdSeq = 1;

                function setStatus(message, isError) {
                  statusBar.className = isError ? 'status error' : 'status ok';
                  statusBar.textContent = message || '';
                }

                function switchPage(page) {
                  const toUpload = page !== 'pickup';
                  uploadPage.classList.toggle('hidden', !toUpload);
                  pickupPage.classList.toggle('hidden', toUpload);
                  navUpload.classList.toggle('active', toUpload);
                  navPickup.classList.toggle('active', !toUpload);
                  if (!toUpload) {
                    window.location.hash = 'pickup';
                  } else {
                    history.replaceState(null, '', window.location.pathname);
                  }
                }

                navUpload.addEventListener('click', function() { switchPage('upload'); });
                navPickup.addEventListener('click', function() { switchPage('pickup'); });

                function switchUploadTab(tab) {
                  const toFile = tab !== 'text';
                  uploadFilePanel.classList.toggle('hidden', !toFile);
                  uploadTextPanel.classList.toggle('hidden', toFile);
                  subNavFile.classList.toggle('active', toFile);
                  subNavText.classList.toggle('active', !toFile);
                }

                subNavFile.addEventListener('click', function() { switchUploadTab('file'); });
                subNavText.addEventListener('click', function() { switchUploadTab('text'); });
                switchUploadTab('file');

                if (window.location.hash === '#pickup') {
                  switchPage('pickup');
                }

                function refreshFileName() {
                  if (!fileQueue.length) {
                    fileNameText.textContent = '未选择文件，可重复添加';
                    return;
                  }
                  fileNameText.textContent = '已选择 ' + fileQueue.length + ' 个文件，可继续添加';
                }

                function updateFileQueueTip() {
                  const base = '已选择 ' + fileQueue.length + ' / ' + MAX_MULTI_FILES + ' 个文件，可继续从其他目录添加。';
                  if (currentCode) {
                    fileQueueTip.textContent = base + ' 当前提取码：' + currentCode;
                  } else {
                    fileQueueTip.textContent = base;
                  }
                }

                function queueStatusText(item) {
                  if (item.status === 'uploading') {
                    return '上传中 ' + Math.floor(item.progress || 0) + '%';
                  }
                  if (item.status === 'success') {
                    return '上传成功';
                  }
                  if (item.status === 'failed') {
                    return '上传失败：' + (item.error || '未知错误');
                  }
                  return '待上传';
                }

                function renderFileQueue() {
                  updateFileQueueTip();
                  refreshFileName();
                  if (!fileQueue.length) {
                    fileQueueList.textContent = '当前没有待上传文件。';
                    return;
                  }

                  fileQueueList.innerHTML = '';
                  fileQueue.forEach(function(item, index) {
                    const wrap = document.createElement('div');
                    wrap.className = 'queue-item';

                    const main = document.createElement('div');
                    main.className = 'queue-item-main';
                    const info = document.createElement('div');
                    info.textContent = (index + 1) + '. ' + item.file.name + ' (' + formatSize(item.file.size) + ')';
                    main.appendChild(info);

                    const actions = document.createElement('div');
                    actions.className = 'queue-actions';

                    if (!uploadRunning && item.status !== 'success') {
                      const removeBtn = document.createElement('button');
                      removeBtn.type = 'button';
                      removeBtn.className = 'secondary';
                      removeBtn.textContent = '移除';
                      removeBtn.onclick = function() {
                        removeQueueItem(item.id);
                      };
                      actions.appendChild(removeBtn);
                    }

                    if (!uploadRunning && item.status === 'failed') {
                      const retryBtn = document.createElement('button');
                      retryBtn.type = 'button';
                      retryBtn.className = 'secondary';
                      retryBtn.textContent = '重试';
                      retryBtn.onclick = function() {
                        retryQueueItem(item.id);
                      };
                      actions.appendChild(retryBtn);
                    }

                    main.appendChild(actions);
                    wrap.appendChild(main);

                    const status = document.createElement('div');
                    status.className = 'queue-status';
                    status.textContent = queueStatusText(item);
                    wrap.appendChild(status);
                    fileQueueList.appendChild(wrap);
                  });
                }

                function addSelectedFiles() {
                  const chosen = Array.prototype.slice.call(fileInput.files || []);
                  if (!chosen.length) return;

                  const remain = MAX_MULTI_FILES - fileQueue.length;
                  if (remain <= 0) {
                    setStatus('同一提取码最多只能上传 ' + MAX_MULTI_FILES + ' 个文件。', true);
                    fileInput.value = '';
                    return;
                  }

                  const accepted = chosen.slice(0, remain);
                  accepted.forEach(function(file) {
                    fileQueue.push({
                      id: queueIdSeq++,
                      file: file,
                      status: 'pending',
                      progress: 0,
                      error: ''
                    });
                  });

                  if (accepted.length < chosen.length) {
                    setStatus('本次超出上限，已仅添加前 ' + accepted.length + ' 个文件。', true);
                  } else {
                    setStatus('已添加 ' + accepted.length + ' 个文件。', false);
                  }

                  fileInput.value = '';
                  renderFileQueue();
                }

                function removeQueueItem(id) {
                  if (uploadRunning) return;
                  fileQueue = fileQueue.filter(function(item) {
                    return item.id !== id;
                  });
                  renderFileQueue();
                }

                function retryQueueItem(id) {
                  const item = fileQueue.find(function(entry) {
                    return entry.id === id;
                  });
                  if (!item || uploadRunning) return;
                  item.status = 'pending';
                  item.progress = 0;
                  item.error = '';
                  renderFileQueue();
                }

                function clearFileQueue() {
                  if (uploadRunning) {
                    setStatus('上传进行中，暂不可清空。', true);
                    return;
                  }
                  fileQueue = [];
                  renderFileQueue();
                }

                fileInput.addEventListener('change', addSelectedFiles);
                refreshFileName();
                renderFileQueue();

                function refreshExpireInput(styleSelect, valueWrap, valueLabel) {
                  const style = styleSelect.value;
                  if (style === 'forever') {
                    valueWrap.classList.add('hidden');
                    return;
                  }
                  valueWrap.classList.remove('hidden');
                  if (style === 'count') {
                    valueLabel.textContent = '下载次数';
                  } else if (style === 'hour') {
                    valueLabel.textContent = '保存小时数';
                  } else if (style === 'minute') {
                    valueLabel.textContent = '保存分钟数';
                  } else {
                    valueLabel.textContent = '保存天数';
                  }
                }

                fileExpireStyle.addEventListener('change', function() {
                  refreshExpireInput(fileExpireStyle, fileExpireValueWrap, fileExpireValueLabel);
                });
                textExpireStyle.addEventListener('change', function() {
                  refreshExpireInput(textExpireStyle, textExpireValueWrap, textExpireValueLabel);
                });
                refreshExpireInput(fileExpireStyle, fileExpireValueWrap, fileExpireValueLabel);
                refreshExpireInput(textExpireStyle, textExpireValueWrap, textExpireValueLabel);

                function readExpireConfig(styleSelect, valueInput) {
                  const style = styleSelect.value || 'day';
                  if (style === 'forever') {
                    return { style: style, value: 1 };
                  }
                  const valueNum = Number(valueInput.value);
                  const safeValue = Number.isFinite(valueNum) && valueNum > 0 ? Math.floor(valueNum) : 1;
                  return { style: style, value: safeValue };
                }

                async function parseApi(resp) {
                  const raw = await resp.text();
                  return parseApiText(raw);
                }

                function parseApiText(raw) {
                  try {
                    return JSON.parse(raw);
                  } catch (error) {
                    throw new Error('服务器返回格式异常：' + raw);
                  }
                }

                function detailText(api) {
                  if (!api) return '未知错误';
                  if (typeof api.detail === 'string' && api.detail) return api.detail;
                  return api.message || '请求失败';
                }

                function showCodeModal(code) {
                  currentCode = String(code || '');
                  codeValue.textContent = currentCode || '-----';
                  codeModalMask.style.display = 'flex';
                }

                function hideCodeModal() {
                  codeModalMask.style.display = 'none';
                }

                async function copyCode() {
                  if (!currentCode) return;
                  if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(currentCode);
                    setStatus('提取码已复制：' + currentCode, false);
                    return;
                  }
                  const temp = document.createElement('input');
                  temp.value = currentCode;
                  document.body.appendChild(temp);
                  temp.select();
                  document.execCommand('copy');
                  document.body.removeChild(temp);
                  setStatus('提取码已复制：' + currentCode, false);
                }

                function goPickupPage() {
                  hideCodeModal();
                  switchPage('pickup');
                  codeInput.value = currentCode;
                }

                function setUploadProgress(percent) {
                  const safe = Math.max(0, Math.min(100, percent || 0));
                  uploadProgressText.textContent = safe.toFixed(0) + '%';
                  uploadProgressBar.style.width = safe + '%';
                }

                function postFormWithProgress(url, formData, onProgress) {
                  return new Promise(function(resolve, reject) {
                    const xhr = new XMLHttpRequest();
                    xhr.open('POST', url, true);
                    xhr.onload = function() {
                      resolve({ status: xhr.status, text: xhr.responseText || '' });
                    };
                    xhr.onerror = function() {
                      reject(new Error('网络连接失败'));
                    };
                    if (xhr.upload && onProgress) {
                      xhr.upload.onprogress = function(event) {
                        if (!event.lengthComputable) return;
                        const percent = event.total > 0 ? (event.loaded / event.total) * 100 : 0;
                        onProgress(percent, event.loaded, event.total);
                      };
                    }
                    xhr.send(formData);
                  });
                }

                async function uploadOneItem(item, expire, groupCodeForRequest) {
                  const data = new FormData();
                  data.append('file', item.file, item.file.name || ('upload_' + Date.now()));
                  data.append('expire_style', expire.style);
                  data.append('expire_value', String(expire.value));
                  data.append('multi_mode', '1');
                  const requestCode = String(groupCodeForRequest || currentCode || '');
                  if (requestCode) {
                    data.append('group_code', requestCode);
                  }

                  const resp = await postFormWithProgress('/share/file/', data, function(percent) {
                    item.progress = percent;
                    renderFileQueue();
                  });

                  const api = parseApiText(resp.text);
                  if (api.code !== 200 || !api.detail || !api.detail.code) {
                    throw new Error(detailText(api));
                  }

                  const returnedCode = String(api.detail.code || '');
                  if (!currentCode) {
                    currentCode = returnedCode;
                    showCodeModal(currentCode);
                  } else if (requestCode && returnedCode && returnedCode !== requestCode) {
                    throw new Error('提取码不一致，请重试');
                  } else if (returnedCode && returnedCode !== currentCode) {
                    throw new Error('提取码不一致，请重试');
                  }

                  return returnedCode;
                }

                async function uploadFileBatch() {
                  if (uploadRunning) return;
                  if (!fileQueue.length) {
                    setStatus('请先添加文件。', true);
                    return;
                  }

                  const pendingItems = fileQueue.filter(function(item) {
                    return item.status === 'pending' || item.status === 'failed';
                  });
                  if (!pendingItems.length) {
                    setStatus('当前没有需要上传的文件。', false);
                    return;
                  }

                  const expire = readExpireConfig(fileExpireStyle, fileExpireValue);
                  if (['day', 'hour', 'minute'].indexOf(expire.style) < 0) {
                    setStatus('多选上传仅支持按有效期销毁（天/小时/分钟）。', true);
                    return;
                  }

                  uploadRunning = true;
                  uploadStartBtn.disabled = true;
                  uploadProgressWrap.classList.remove('hidden');
                  setUploadProgress(0);
                  renderFileQueue();

                  let cursor = 0;
                  let finished = 0;
                  let success = 0;
                  let failed = 0;
                  let batchCode = String(currentCode || '');

                  if (!batchCode && pendingItems.length > 0) {
                    const firstItem = pendingItems[0];
                    firstItem.status = 'uploading';
                    firstItem.progress = 0;
                    firstItem.error = '';
                    renderFileQueue();

                    try {
                      const returned = await uploadOneItem(firstItem, expire, '');
                      firstItem.status = 'success';
                      firstItem.progress = 100;
                      success += 1;
                      finished += 1;
                      batchCode = String(currentCode || returned || '');
                    } catch (error) {
                      firstItem.status = 'failed';
                      firstItem.error = error && error.message ? error.message : '网络错误';
                      firstItem.progress = 0;
                      failed += 1;
                      finished += 1;
                      setUploadProgress((finished / pendingItems.length) * 100);
                      uploadRunning = false;
                      uploadStartBtn.disabled = false;
                      renderFileQueue();
                      setStatus('首个文件上传失败，未生成提取码，请重试。', true);
                      return;
                    }

                    setUploadProgress((finished / pendingItems.length) * 100);
                    renderFileQueue();
                  }

                  const uploadTargets = pendingItems.filter(function(item) {
                    return item.status === 'pending' || item.status === 'failed';
                  });

                  if (!uploadTargets.length) {
                    uploadRunning = false;
                    uploadStartBtn.disabled = false;
                    setStatus('上传完成：成功 ' + success + ' 个。当前提取码：' + currentCode, false);
                    return;
                  }

                  async function worker() {
                    while (true) {
                      const index = cursor++;
                      if (index >= uploadTargets.length) return;
                      const item = uploadTargets[index];
                      item.status = 'uploading';
                      item.progress = 0;
                      item.error = '';
                      renderFileQueue();
                      try {
                        await uploadOneItem(item, expire, batchCode);
                        item.status = 'success';
                        item.progress = 100;
                        success += 1;
                      } catch (error) {
                        item.status = 'failed';
                        item.error = error && error.message ? error.message : '网络错误';
                        item.progress = 0;
                        failed += 1;
                      }
                      finished += 1;
                      setUploadProgress((finished / pendingItems.length) * 100);
                      renderFileQueue();
                    }
                  }

                  const workers = [];
                  const workerCount = Math.min(MAX_PARALLEL_UPLOADS, pendingItems.length);
                  for (let i = 0; i < workerCount; i += 1) {
                    workers.push(worker());
                  }
                  await Promise.all(workers);

                  uploadRunning = false;
                  uploadStartBtn.disabled = false;

                  if (failed > 0) {
                    setStatus('上传完成：成功 ' + success + ' 个，失败 ' + failed + ' 个，可重试失败项。', true);
                  } else {
                    setStatus('上传完成：成功 ' + success + ' 个。当前提取码：' + currentCode, false);
                  }
                }

                async function shareText() {
                  const text = document.getElementById('textInput').value.trim();
                  if (!text) {
                    setStatus('文本内容不能为空。', true);
                    return;
                  }
                  const expire = readExpireConfig(textExpireStyle, textExpireValue);
                  const data = new FormData();
                  data.append('text', text);
                  data.append('expire_style', expire.style);
                  data.append('expire_value', String(expire.value));
                  try {
                    const resp = await fetch('/share/text/', { method: 'POST', body: data });
                    const api = await parseApi(resp);
                    if (api.code === 200 && api.detail && api.detail.code) {
                      setStatus('文本分享成功。', false);
                      showCodeModal(api.detail.code);
                    } else {
                      setStatus('提交失败：' + detailText(api), true);
                    }
                  } catch (error) {
                    setStatus('提交失败：' + (error && error.message ? error.message : '网络错误'), true);
                  }
                }

                function formatSize(size) {
                  if (!Number.isFinite(size) || size < 0) return '-';
                  if (size < 1024) return size + ' B';
                  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + ' KB';
                  if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(2) + ' MB';
                  return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
                }

                function buildDownloadButton(text, url, primary) {
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.className = primary ? 'action' : 'secondary';
                  button.textContent = text;
                  button.onclick = function() {
                    window.open(url, '_blank');
                  };
                  return button;
                }

                function renderResult(detail) {
                  resultCard.classList.remove('hidden');
                  pendingDownloadUrl = '';
                  resultBox.innerHTML = '';
                  const name = detail && detail.name ? detail.name : '未知';
                  const size = detail && typeof detail.size !== 'undefined' ? Number(detail.size) : -1;
                  const destroyInfo = detail && detail.destroy_info ? String(detail.destroy_info) : '销毁信息未知';
                  const destroyAt = detail && detail.destroy_at_text ? String(detail.destroy_at_text) : '';
                  const destroyAtPart = destroyAt ? (' | 销毁时间：' + destroyAt) : '';
                  resultMeta.textContent = '名称：' + name + ' | 大小：' + formatSize(size) + ' | ' + destroyInfo + destroyAtPart;

                  const fileList = detail && Array.isArray(detail.files) ? detail.files : [];
                  const packageDownloadUrl = detail && detail.package_download_url ? String(detail.package_download_url) : '';
                  const downloadUrl = detail && detail.download_url ? String(detail.download_url) : '';
                  const text = detail ? detail.text : '';
                  const type = detail && detail.file_type ? String(detail.file_type) : '';
                  const isMulti = type === 'multi_file' || fileList.length > 1;
                  const isFile = (detail && detail.file_type === 'file') ||
                    (!!downloadUrl) ||
                    (typeof text === 'string' && text.indexOf('/share/download?') === 0);

                  if (isMulti) {
                    const title = document.createElement('div');
                    title.textContent = '识别为多文件，共 ' + fileList.length + ' 个，可逐个下载或打包 ZIP。';
                    resultBox.appendChild(title);

                    if (packageDownloadUrl) {
                      const packageRow = document.createElement('div');
                      packageRow.className = 'row';
                      packageRow.appendChild(buildDownloadButton('打包下载 ZIP', packageDownloadUrl, true));
                      resultBox.appendChild(packageRow);
                    }

                    const listWrap = document.createElement('div');
                    listWrap.className = 'download-list';
                    fileList.forEach(function(item, index) {
                      if (!item || !item.download_url) return;
                      const card = document.createElement('div');
                      card.className = 'download-item';
                      const titleLine = document.createElement('div');
                      titleLine.textContent = (index + 1) + '. ' + String(item.name || '未命名文件') + ' (' + formatSize(Number(item.size)) + ')';
                      card.appendChild(titleLine);

                      if (item.destroy_info) {
                        const tip = document.createElement('div');
                        tip.className = 'tip';
                        tip.textContent = String(item.destroy_info);
                        card.appendChild(tip);
                      }

                      const row = document.createElement('div');
                      row.className = 'row';
                      row.appendChild(buildDownloadButton('下载此文件', String(item.download_url), true));
                      card.appendChild(row);
                      listWrap.appendChild(card);
                    });
                    resultBox.appendChild(listWrap);
                    return;
                  }

                  if (isFile) {
                    const finalUrl = downloadUrl || text;
                    pendingDownloadUrl = finalUrl;
                    const line = document.createElement('div');
                    line.textContent = '识别为文件，请确认后开始下载。';
                    resultBox.appendChild(line);
                    const row = document.createElement('div');
                    row.className = 'row';
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'action';
                    btn.textContent = '确认下载';
                    btn.onclick = confirmDownload;
                    row.appendChild(btn);
                    resultBox.appendChild(row);
                  } else {
                    resultBox.textContent = String(text || '');
                  }
                }

                function confirmDownload() {
                  if (!pendingDownloadUrl) {
                    setStatus('当前没有可下载文件。', true);
                    return;
                  }
                  window.open(pendingDownloadUrl, '_blank');
                }

                async function queryCode() {
                  const code = codeInput.value.trim();
                  if (!code) {
                    setStatus('请输入提取码。', true);
                    return;
                  }
                  try {
                    const data = new FormData();
                    data.append('code', code);
                    const resp = await fetch('/share/select/', {
                      method: 'POST',
                      body: data
                    });
                    const api = await parseApi(resp);
                    if (api.code === 200 && api.detail) {
                      setStatus('提取码有效。', false);
                      renderResult(api.detail);
                    } else {
                      resultCard.classList.add('hidden');
                      setStatus('提取失败：' + detailText(api), true);
                    }
                  } catch (error) {
                    resultCard.classList.add('hidden');
                    setStatus('提取失败：' + (error && error.message ? error.message : '网络错误'), true);
                  }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}

private fun buildSelectToken(code: String): String {
    val section = currentTokenSection()
    return sha256("${code}${section}${FILE_CODE_BOX_ADMIN_TOKEN}")
}

private fun buildFileToken(code: String, fileKey: String): String {
    val section = currentTokenSection()
    return sha256("FILE:${code}:${fileKey}:${section}:${FILE_CODE_BOX_ADMIN_TOKEN}")
}

private fun buildZipToken(code: String): String {
    val section = currentTokenSection()
    return sha256("ZIP:${code}:${section}:${FILE_CODE_BOX_ADMIN_TOKEN}")
}

private fun currentTokenSection(): Long {
    return (System.currentTimeMillis() / 1_000_000L) * 1000L
}

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun maxSaveLimitDetail(): String {
    if (FILE_CODE_BOX_MAX_SAVE_SECONDS <= 0) return "当前未限制最长保存时间"

    var remain = FILE_CODE_BOX_MAX_SAVE_SECONDS
    val days = remain / 86_400
    remain %= 86_400
    val hours = remain / 3_600
    remain %= 3_600
    val minutes = remain / 60
    val seconds = remain % 60

    val sb = StringBuilder()
    if (days > 0) sb.append(days).append("天")
    if (hours > 0) sb.append(hours).append("小时")
    if (minutes > 0) sb.append(minutes).append("分钟")
    if (seconds > 0) sb.append(seconds).append("秒")
    val pretty = if (sb.isEmpty()) "0秒" else sb.toString()
    return "限制最长时间为 ${pretty}，可换用其他方式"
}

private fun formatDateTime(timestampMs: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestampMs))
}

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0秒"
    var remain = totalSeconds
    val days = remain / 86_400
    remain %= 86_400
    val hours = remain / 3_600
    remain %= 3_600
    val minutes = remain / 60
    val seconds = remain % 60

    val parts = mutableListOf<String>()
    if (days > 0) parts += "${days}天"
    if (hours > 0) parts += "${hours}小时"
    if (minutes > 0) parts += "${minutes}分钟"
    if (seconds > 0 && parts.size < 3) parts += "${seconds}秒"
    return parts.joinToString("")
}

private fun sanitizeFileName(name: String): String {
    var value = name.replace('\\', '/').substringAfterLast('/')
    value = value.replace(Regex("[\\\\/*?:\"<>|\\x00-\\x1F]"), "_")
    value = value.replace(' ', '_')
    value = value.replace(Regex("_+"), "_")
    value = value.trim('.', '_', ' ')
    if (value.isBlank()) value = "unnamed_file"
    return value.take(255)
}

private fun sanitizeZipEntryName(name: String): String {
    var value = name.replace('\\', '/').substringAfterLast('/')
    value = value.replace(Regex("[\\x00-\\x1F]"), "_")
    value = value.replace("..", "_")
    value = value.trim()
    if (value.isBlank()) value = "file"
    return value.take(255)
}

private fun uniqueZipEntryName(baseName: String, usedNames: MutableSet<String>): String {
    val base = sanitizeZipEntryName(baseName).ifBlank { "file" }
    if (usedNames.add(base)) return base

    val prefix = base.substringBeforeLast('.', base)
    val suffix = base.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".${it}" }
    var index = 1
    while (true) {
        val candidate = "${prefix}_${index}${suffix}"
        if (usedNames.add(candidate)) return candidate
        index++
    }
}

private fun guessMimeTypeByFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    if (extension.isBlank()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun defaultFileNameForMime(mimeType: String): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty()
    return if (extension.isBlank()) {
        "upload_${System.currentTimeMillis()}"
    } else {
        "upload_${System.currentTimeMillis()}.$extension"
    }
}

private fun urlEncode(value: String): String {
    return runCatching {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }.getOrElse { value }
}
