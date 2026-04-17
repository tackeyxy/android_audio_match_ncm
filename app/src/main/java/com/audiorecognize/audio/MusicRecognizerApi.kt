package com.audiorecognize.audio

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MusicRecognizerApi {

    data class SongResult(
        val songId: Long,
        val songName: String,
        val artistName: String,
        val albumName: String,
        val songUrl: String,
        val startTime: Int = 0,
        val duration: Int = 0,
        val popularity: Float = 0f,
        val score: Int = 0,
        val hMusic: MusicInfo? = null,
        val mMusic: MusicInfo? = null,
        val lMusic: MusicInfo? = null,
        val mvid: Long = 0,
        val rtUrl: String? = null,
        val ftype: Int = 0,
        val rtype: Int = 0,
        val rurl: String? = null,
        val status: Int = 0,
        val fee: Int = 0,
        val copyrightId: Long = 0,
        val disc: String = "",
        val no: Int = 0,
        val starred: Boolean = false,
        val playedNum: Long = 0,
        val dayPlays: Int = 0,
        val hearTime: Long = 0,
        val commentThreadId: String = "",
        val copyFrom: String = "",
        val copyright: Int = 0,
        val originCoverType: Int = 0,
        val single: Int = 0,
        val noCopyrightRcmd: String? = null,
        val canSubscribe: Int = 0,
        val hasSubscribe: Int = 0,
        val mark: Int = 0,
        val albumId: Long = 0,
        val albumPicUrl: String = "",
        val albumBlurPicUrl: String = "",
        val albumPublishTime: Long = 0,
        val artistId: Long = 0,
        val artistPicUrl: String = "",
        val privilege: PrivilegeInfo? = null
    )

    data class MusicInfo(
        val id: Long = 0,
        val name: String? = null,
        val size: Long = 0,
        val extension: String = "",
        val sr: Int = 0,
        val dfsId: Long = 0,
        val bitrate: Int = 0,
        val playTime: Int = 0,
        val volumeDelta: Float = 0f
    )

    data class PrivilegeInfo(
        val id: Long = 0,
        val fee: Int = 0,
        val payed: Int = 0,
        val st: Int = 0,
        val pl: Int = 0,
        val dl: Int = 0,
        val sp: Int = 0,
        val cp: Int = 0,
        val subp: Int = 0,
        val cs: Boolean = false,
        val maxbr: Int = 0,
        val fl: Int = 0,
        val toast: Boolean = false,
        val flag: Int = 0,
        val preSell: Boolean = false,
        val playMaxbr: Int = 0,
        val downloadMaxbr: Int = 0,
        val maxBrLevel: String = "",
        val playMaxBrLevel: String = "",
        val dlLevel: String = "",
        val flLevel: String = "",
        val chargeInfoList: List<ChargeInfo> = emptyList()
    )

    data class ChargeInfo(
        val rate: Int = 0,
        val chargeUrl: String? = null,
        val chargeMessage: String? = null,
        val chargeType: Int = 0
    )

    suspend fun recognize(fingerprint: String, duration: Int = 3): List<SongResult>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://interface.music.163.com/api/music/audio/match"
            
            val queryParams = mapOf(
                "sessionId" to "-android-${System.currentTimeMillis()}",
                "algorithmCode" to "shazam_v2",
                "duration" to duration.toString(),
                "rawdata" to fingerprint,
                "times" to "1",
                "decrypt" to "1"
            )

            val fullUrl = buildString {
                append(url)
                append("?")
                append(queryParams.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" })
            }

            android.util.Log.d("MusicRecognizer", "Request URL: $fullUrl")
            android.util.Log.d("MusicRecognizer", "Fingerprint length: ${fingerprint.length}")

            val connection = URL(fullUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.74 Mobile Safari/537.36")
            connection.setRequestProperty("Referer", "https://music.163.com/")
            connection.setRequestProperty("Origin", "https://music.163.com")
            connection.setRequestProperty("Cookie", "appver=3.5.1")

            val responseCode = connection.responseCode
            android.util.Log.d("MusicRecognizer", "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                android.util.Log.d("MusicRecognizer", "Response length: ${response.length}")
                android.util.Log.d("MusicRecognizer", "Response preview: ${response.take(500)}")
                parseResponse(response)
            } else {
                android.util.Log.e("MusicRecognizer", "HTTP error: $responseCode")
                val errorResponse = connection.errorStream?.bufferedReader()?.readText()
                android.util.Log.e("MusicRecognizer", "Error response: $errorResponse")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicRecognizer", "Exception: ${e.message}", e)
            null
        }
    }

    private fun parseResponse(response: String): List<SongResult>? {
        return try {
            val jsonObject = Gson().fromJson(response, JsonObject::class.java)
            val code = jsonObject.get("code")?.asInt

            if (code == 200) {
                val data = jsonObject.getAsJsonObject("data")
                if (data != null && data.has("result")) {
                    val result = data.get("result")
                    if (result is JsonArray) {
                        val songs = mutableListOf<SongResult>()
                        for (element in result) {
                            try {
                                val songObj = element.asJsonObject
                                val song = songObj.getAsJsonObject("song") ?: continue

                                val songId = song.get("id")?.asLong ?: continue
                                val songName = song.get("name")?.asString ?: "Unknown"
                                val album = song.getAsJsonObject("album")
                                val albumName = album?.get("name")?.asString ?: "Unknown"
                                val albumId = album?.get("id")?.asLong ?: 0L
                                val albumPicUrl = album?.get("picUrl")?.asString ?: ""
                                val albumBlurPicUrl = album?.get("blurPicUrl")?.asString ?: ""
                                val albumPublishTime = album?.get("publishTime")?.asLong ?: 0L

                                val artists = song.getAsJsonArray("artists")
                                val artistName = if (artists != null && artists.size() > 0) {
                                    artists[0].asJsonObject.get("name")?.asString ?: "Unknown"
                                } else "Unknown"
                                val artistId = if (artists != null && artists.size() > 0) {
                                    artists[0].asJsonObject.get("id")?.asLong ?: 0L
                                } else 0L
                                val artistPicUrl = if (artists != null && artists.size() > 0) {
                                    artists[0].asJsonObject.get("picUrl")?.asString ?: ""
                                } else ""

                                fun safeGetString(json: JsonObject?, key: String): String? {
                                    return try { json?.get(key)?.asString } catch (e: Exception) { null }
                                }

                                fun safeGetLong(json: JsonObject?, key: String): Long {
                                    return try { json?.get(key)?.asLong ?: 0L } catch (e: Exception) { 0L }
                                }

                                fun safeGetInt(json: JsonObject?, key: String): Int {
                                    return try { json?.get(key)?.asInt ?: 0 } catch (e: Exception) { 0 }
                                }

                                val hMusicObj = song.getAsJsonObject("hMusic")
                                val mMusicObj = song.getAsJsonObject("mMusic")
                                val lMusicObj = song.getAsJsonObject("lMusic")

                                val hMusic = if (hMusicObj != null) MusicInfo(
                                    id = safeGetLong(hMusicObj, "id"),
                                    name = safeGetString(hMusicObj, "name"),
                                    size = safeGetLong(hMusicObj, "size"),
                                    extension = safeGetString(hMusicObj, "extension") ?: "",
                                    sr = safeGetInt(hMusicObj, "sr"),
                                    dfsId = safeGetLong(hMusicObj, "dfsId"),
                                    bitrate = safeGetInt(hMusicObj, "bitrate"),
                                    playTime = safeGetInt(hMusicObj, "playTime"),
                                    volumeDelta = 0f
                                ) else null

                                val mMusic = if (mMusicObj != null) MusicInfo(
                                    id = safeGetLong(mMusicObj, "id"),
                                    name = safeGetString(mMusicObj, "name"),
                                    size = safeGetLong(mMusicObj, "size"),
                                    extension = safeGetString(mMusicObj, "extension") ?: "",
                                    sr = safeGetInt(mMusicObj, "sr"),
                                    dfsId = safeGetLong(mMusicObj, "dfsId"),
                                    bitrate = safeGetInt(mMusicObj, "bitrate"),
                                    playTime = safeGetInt(mMusicObj, "playTime"),
                                    volumeDelta = 0f
                                ) else null

                                val lMusic = if (lMusicObj != null) MusicInfo(
                                    id = safeGetLong(lMusicObj, "id"),
                                    name = safeGetString(lMusicObj, "name"),
                                    size = safeGetLong(lMusicObj, "size"),
                                    extension = safeGetString(lMusicObj, "extension") ?: "",
                                    sr = safeGetInt(lMusicObj, "sr"),
                                    dfsId = safeGetLong(lMusicObj, "dfsId"),
                                    bitrate = safeGetInt(lMusicObj, "bitrate"),
                                    playTime = safeGetInt(lMusicObj, "playTime"),
                                    volumeDelta = 0f
                                ) else null

                                val privilegeObj = song.getAsJsonObject("privilege")
                                val privilege = if (privilegeObj != null) PrivilegeInfo(
                                    id = safeGetLong(privilegeObj, "id"),
                                    fee = safeGetInt(privilegeObj, "fee"),
                                    payed = safeGetInt(privilegeObj, "payed"),
                                    st = safeGetInt(privilegeObj, "st"),
                                    pl = safeGetInt(privilegeObj, "pl"),
                                    dl = safeGetInt(privilegeObj, "dl"),
                                    sp = safeGetInt(privilegeObj, "sp"),
                                    cp = safeGetInt(privilegeObj, "cp"),
                                    subp = safeGetInt(privilegeObj, "subp"),
                                    cs = false,
                                    maxbr = safeGetInt(privilegeObj, "maxbr"),
                                    fl = safeGetInt(privilegeObj, "fl"),
                                    toast = false,
                                    flag = safeGetInt(privilegeObj, "flag"),
                                    preSell = false,
                                    playMaxbr = safeGetInt(privilegeObj, "playMaxbr"),
                                    downloadMaxbr = safeGetInt(privilegeObj, "downloadMaxbr"),
                                    maxBrLevel = safeGetString(privilegeObj, "maxBrLevel") ?: "",
                                    playMaxBrLevel = safeGetString(privilegeObj, "playMaxBrLevel") ?: "",
                                    dlLevel = safeGetString(privilegeObj, "dlLevel") ?: "",
                                    flLevel = safeGetString(privilegeObj, "flLevel") ?: "",
                                    chargeInfoList = emptyList()
                                ) else null

                                songs.add(SongResult(
                                    songId = songId,
                                    songName = songName,
                                    artistName = artistName,
                                    albumName = albumName,
                                    songUrl = "https://music.163.com/song?id=$songId",
                                    startTime = safeGetInt(songObj, "startTime"),
                                    duration = safeGetInt(song, "duration"),
                                    popularity = 0f,
                                    score = safeGetInt(song, "score"),
                                    hMusic = hMusic,
                                    mMusic = mMusic,
                                    lMusic = lMusic,
                                    mvid = safeGetLong(song, "mvid"),
                                    rtUrl = safeGetString(song, "rtUrl"),
                                    ftype = safeGetInt(song, "ftype"),
                                    rtype = safeGetInt(song, "rtype"),
                                    rurl = safeGetString(song, "rurl"),
                                    status = safeGetInt(song, "status"),
                                    fee = safeGetInt(song, "fee"),
                                    copyrightId = safeGetLong(song, "copyrightId"),
                                    disc = safeGetString(song, "disc") ?: "",
                                    no = safeGetInt(song, "no"),
                                    starred = false,
                                    playedNum = 0L,
                                    dayPlays = 0,
                                    hearTime = 0L,
                                    commentThreadId = safeGetString(song, "commentThreadId") ?: "",
                                    copyFrom = "",
                                    copyright = safeGetInt(song, "copyright"),
                                    originCoverType = 0,
                                    single = 0,
                                    noCopyrightRcmd = null,
                                    canSubscribe = 0,
                                    hasSubscribe = 0,
                                    mark = safeGetInt(song, "mark"),
                                    albumId = albumId,
                                    albumPicUrl = albumPicUrl,
                                    albumBlurPicUrl = albumBlurPicUrl,
                                    albumPublishTime = albumPublishTime,
                                    artistId = artistId,
                                    artistPicUrl = artistPicUrl,
                                    privilege = privilege
                                ))
                            } catch (e: Exception) {
                                android.util.Log.e("MusicRecognizer", "Parse song error: ${e.message}")
                            }
                        }
                        return songs
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}