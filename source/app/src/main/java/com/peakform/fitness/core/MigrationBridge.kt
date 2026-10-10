package com.peakform.fitness.core

import com.peakform.fitness.ProLog
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream

/**
 * MigrationBridge — invisible one-shot reader that pulls the legacy WebView app's data
 * (Dexie IndexedDB "WorkoutApp" + p4_vault + per-origin localStorage) into the native store.
 *
 * How it works: the original shell served assets at https://<profileId>.appassets.local —
 * the IndexedDB/localStorage origin is bound to that HOST. By intercepting requests for the
 * same host pattern from OUR WebView and serving a small migrate.html from assets, the hidden
 * page runs in the SAME origin and can read the old databases directly. Profiles come from the
 * native "registry" SharedPreferences the original shell wrote (ProfileRegistry).
 *
 * After import the WebView is destroyed; it is NEVER used for UI. This is not the hybrid
 * architecture — it's a data shuttle that runs once.
 */
object MigrationBridge {
    const val HOST_SUFFIX = ".appassets.local"
    const val DONE_KEY = "p4_native_migration_done"

    data class Progress(val phase: String, val profile: String = "", val workouts: Int = 0, val exercises: Int = 0)

    @Volatile var running: Boolean = false
        private set

    fun isDone(ctx: Context): Boolean = ProPrefs.get(ctx, DONE_KEY) == "true"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(ctx: Context, onProgress: (Progress) -> Unit): Boolean = withContext(Dispatchers.Main) {
        if (running || isDone(ctx)) return@withContext true
        running = true
        try {
            val (profiles, active, _) = ProfileRegistry.read(ctx)
            ProLog.i("MIGRATE", "registry profiles=${profiles.size} active=$active")

            // Legacy JS-side profiles (p4_profiles in localStorage of each origin) are discovered
            // by the bridge page itself; native registry entries define the host list.
            val hosts = LinkedHashSet<String>()
            active?.let { hosts.add(it) }
            profiles.forEach { hosts.add(it.id) }
            // Fallback: scan webview IndexedDB dir names for https_<host>_0 patterns
            try {
                val idbDir = java.io.File(ctx.applicationInfo.dataDir, "app_webview/Default/IndexedDB")
                if (idbDir.exists()) {
                    idbDir.listFiles()?.forEach { f ->
                        val n = f.name
                        if (n.startsWith("https_") && n.contains(HOST_SUFFIX)) {
                            // e.g. https_p1.appassets.local_0.indexeddb.leveldb
                            val m = Regex("https_(.+?)_0\\.indexeddb").find(n)
                            m?.groupValues?.get(1)?.let { hosts.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
            if (hosts.isEmpty()) {
                ProLog.i("MIGRATE", "no legacy hosts found — nothing to migrate")
                ProPrefs.put(ctx, DONE_KEY, "true")
                return@withContext true
            }
            ProLog.i("MIGRATE", "hosts to scan: $hosts")

            var importedAny = false
            val collected = mutableListOf<JsonObject>()

            for (host in hosts) {
                val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(ctx)
                webView.layoutParams = android.view.ViewGroup.LayoutParams(0, 0)
                webView.visibility = android.view.View.GONE
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.databaseEnabled = true
                webView.settings.allowFileAccess = false
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun dump(json: String) {
                        try {
                            val obj = Json.parseToJsonElement(json).jsonObject
                            synchronized(collected) { collected.add(obj) }
                            latch.complete(true)
                        } catch (e: Exception) {
                            ProLog.e("MIGRATE", "dump parse failed: ${e.message}")
                            latch.complete(false)
                        }
                    }
                    @JavascriptInterface
                    fun fail(reason: String) {
                        ProLog.e("MIGRATE", "bridge page failed for host: $reason")
                        latch.complete(false)
                    }
                }, "ProMigration")

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val host = request.url.host ?: return null
                        if (host.endsWith(HOST_SUFFIX)) {
                            val path = request.url.path ?: "/migrate.html"
                            return serveAsset(ctx, path)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        ProLog.i("MIGRATE", "page finished: $url")
                        handler.postDelayed({
                            view.evaluateJavascript(
                                "window.__ProMigrate && window.__ProMigrate.run('$host');",
                                null,
                            )
                        }, 400)
                        // hard timeout
                        handler.postDelayed({ if (!latch.isCompleted) latch.complete(false) }, 12000)
                    }
                }

                ProLog.i("MIGRATE", "loading https://$host$HOST_SUFFIX/migrate.html")
                onProgress(Progress("reading", host))
                webView.loadUrl("https://$host$HOST_SUFFIX/migrate.html")
                val ok = try { latch.await() } catch (_: Exception) { false }
                webView.destroy()
                ProLog.i("MIGRATE", "host $host -> ok=$ok dumps=${collected.size}")
                if (ok) importedAny = true
            }

            if (collected.isNotEmpty()) {
                onProgress(Progress("importing"))
                val merged = ImportMerger.merge(ctx, collected)
                ProLog.i("MIGRATE", "merged: workouts=${merged.first} exercises=${merged.second}")
                onProgress(Progress("done", workouts = merged.first, exercises = merged.second))
            }
            ProPrefs.put(ctx, DONE_KEY, "true")
            importedAny || collected.isNotEmpty()
        } finally {
            running = false
        }
    }

    private fun serveAsset(ctx: Context, path: String): WebResourceResponse? {
        val assetPath = when {
            path.startsWith("/") -> path.trimStart('/')
            else -> path
        }.ifBlank { "migrate.html" }
        val finalPath = if (assetPath == "migrate.html") "migrate.html" else "cdn/$assetPath"
        return try {
            val stream = ctx.assets.open(finalPath)
            val mime = when {
                finalPath.endsWith(".html") -> "text/html"
                finalPath.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, "utf-8", stream)
        } catch (_: Exception) {
            try {
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<html>not found</html>".toByteArray()))
            } catch (_: Exception) { null }
        }
    }
}

/** Merges one or more origin dumps into the native store. */
object ImportMerger {
    suspend fun merge(ctx: Context, dumps: List<JsonObject>): Pair<Int, Int> {
        var workouts = 0
        var exercisesCount = 0

        // Choose best workoutData: prefer the one with most completed workouts
        var bestData: WorkoutData? = null
        var bestScore = -1
        var bestLs: Map<String, String>? = null

        for (dump in dumps) {
            // 1. Dexie stores → workoutData aggregate
            val wArr = dump["workouts"] as? kotlinx.serialization.json.JsonArray
            val exObj = dump["exercises"] as? JsonObject
            val userEl = (dump["user"] as? JsonObject)?.get("value")
            val savedEl = (dump["savedWorkout"] as? JsonObject)?.get("value")
            val ls = dump["localStorage"] as? JsonObject
            val vault = dump["p4_vault"] as? JsonObject

            if (ls != null && bestLs == null) {
                bestLs = ls.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
            }

            if (wArr != null || exObj != null) {
                val wList = wArr?.mapNotNull { el ->
                    try { ProJson.decode(WorkoutRecord.serializer(), el.toString()) } catch (_: Exception) { null }
                } ?: emptyList()
                val exMap = exObj?.mapNotNull { (k, v) ->
                    try { k to ProJson.decode(ExerciseRecord.serializer(), v.toString()) } catch (_: Exception) { null }
                }?.toMap() ?: emptyMap()
                val user = userEl?.let { try { ProJson.decode(UserProfile.serializer(), it.toString()) } catch (_: Exception) { null } } ?: UserProfile()
                val score = wList.count { it.dateCompleted != null } + wList.size
                if (score > bestScore) {
                    bestScore = score
                    bestData = WorkoutData(user = user, workouts = wList.sortedBy { it.date }, exercises = exMap)
                }
                workouts += wList.size
                exercisesCount += exMap.size
            }

            // 2. savedWorkout (current draft) — keep if fresh
            savedEl?.let { sv ->
                try {
                    val draft = ProJson.decode(WorkoutRecord.serializer(), sv.toString())
                    if (draft.exercises.isNotEmpty() && draft.dateCompleted == null &&
                        ProState.currentWorkout == null
                    ) {
                        ProState.currentWorkout = draft
                    }
                } catch (_: Exception) {}
            }

            // 3. vault snapshots (IndexedDB p4_vault) — store as guarded snapshots, never auto-restore
            vault?.forEach { (pid, snapEl) ->
                try {
                    val inner = (snapEl as? JsonObject)?.get("data")?.toString() ?: return@forEach
                    ProStore(ctx).snapshotVault("legacy-$pid", ProJson.decode(WorkoutData.serializer(), inner), null)
                } catch (_: Exception) {}
            }
        }

        // 4. legacy localStorage profile-data keys (p4_profile_data_*) may hold richer data
        bestLs?.forEach { (k, v) ->
            if (k.startsWith("p4_profile_data_") && v.isNotBlank()) {
                try {
                    val pd = ProJson.decode(WorkoutData.serializer(), v)
                    val score = pd.workouts.count { it.dateCompleted != null } + pd.workouts.size
                    if (score > bestScore) {
                        bestScore = score
                        bestData = pd
                    }
                } catch (_: Exception) {}
            }
        }

        bestData?.let { imported ->
            val cur = ProState.data
            // merge with whatever native state exists (should be empty on first run)
            val mergedWorkouts = (imported.workouts + cur.workouts).distinctBy { it.id.ifBlank { it.date + it.name } }.sortedBy { it.date }
            val mergedEx = cur.exercises.toMutableMap()
            imported.exercises.forEach { (k, v) -> if (!mergedEx.containsKey(k)) mergedEx[k] = v }
            ProState.data = imported.copy(
                workouts = mergedWorkouts,
                exercises = mergedEx,
                user = imported.user.copy(settings = ProState.mergeSettings(cur.user.settings, imported.user.settings)),
            )
        }

        // 5. restore legacy localStorage prefs (themes, power, notif, profiles registry, custom ex)
        bestLs?.forEach { (k, v) ->
            if (k == "workoutEmergencyBackup" || k == "workoutData" || k.startsWith("draft_")) return@forEach
            ProPrefs.put(ctx, k, v)
        }

        ProState.saveWorkoutData()
        ProState.notifyChanged()
        return Pair(ProState.data.workouts.size, ProState.data.exercises.size)
    }
}
