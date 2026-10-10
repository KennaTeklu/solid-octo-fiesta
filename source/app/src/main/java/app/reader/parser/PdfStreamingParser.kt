package app.reader.parser

import android.content.Context
import android.net.Uri
import app.reader.ReaderApp
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming PDF parser — Batch 2 (PDF-08).
 *
 * Reads the PDF page-by-page on a background thread. Each completed page is
 *   1. appended to a partial [ParsedDocument] (single chapter, growing list
 *      of paragraphs),
 *   2. emitted via [progress] so the UI can update the overlay / bar,
 *   3. appended to a sidecar JSON file so a killed process can resume.
 *
 * The reader is fed the partial [ParsedDocument] as soon as the first page
 * is ready — it does not wait for the whole file to parse.
 *
 * Resume: if a sidecar JSON file exists for the docId and contains fewer
 * pages than the document, the parser loads the already-parsed pages from
 * the sidecar and continues parsing the remaining pages from the source
 * PDF. The progress bar jumps to the persisted percentage on resume.
 *
 * Why a sidecar JSON and not Room? The partial body is large (potentially
 * hundreds of KB) and updates often; Room would write a lot. A line-delimited
 * JSON file (one object per page) lets us append cheaply and reload in one
 * pass on resume.
 */
object PdfStreamingParser {

    /** Streaming state surfaced to the UI. */
    sealed interface State {
        data object Idle : State
        data class Loading(
            val docId: String,
            val title: String,
            val pagesDone: Int,
            val pagesTotal: Int,
            val charsSoFar: Int,
            /** Partial doc — readable from the very first page. */
            val partial: ParsedDocument,
            /** True when restored from a previous run, so UI can show "Resumed". */
            val resumed: Boolean
        ) : State
        data class Done(
            val docId: String,
            val title: String,
            val partial: ParsedDocument,
            val pagesDone: Int,
            val pagesTotal: Int,
            val charsSoFar: Int
        ) : State
        data class Error(val docId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Currently active parse job per docId — so we don't double-start on recomposition. */
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start streaming a PDF identified by its [docId]. The source is read
     * from [bytes] (loaded from disk by [DocumentRepository]).
     *
     * The first emission happens immediately: either a resumed [State.Loading]
     * (if a sidecar exists for [docId]) or a fresh [State.Loading] with an
     * empty partial doc. Subsequent emissions update the partial doc and
     * the live numbers as each page completes.
     *
     * Safe to call multiple times for the same [docId] — the existing job
     * is returned and no duplicate parse starts.
     */
    fun start(
        docId: String,
        title: String,
        bytes: ByteArray,
        mime: String,
        resumeFromSidecar: Boolean = true
    ): Job {
        activeJobs[docId]?.let { if (it.isActive) return it }
        val job = scope.launch {
            try {
                runParse(docId, title, bytes, mime, resumeFromSidecar)
            } catch (t: Throwable) {
                _state.value = State.Error(docId, t.message ?: "Parse failed")
                activeJobs.remove(docId)
            }
        }
        activeJobs[docId] = job
        return job
    }

    /** Cancel an in-flight parse for [docId], if any. */
    fun cancel(docId: String) {
        activeJobs.remove(docId)?.cancel()
    }

    /** Reset state to Idle for [docId]. Called when the reader screen exits. */
    fun reset(docId: String) {
        cancel(docId)
        if ((_state.value as? State.Loading)?.docId == docId ||
            (_state.value as? State.Done)?.docId == docId ||
            (_state.value as? State.Error)?.docId == docId) {
            _state.value = State.Idle
        }
    }

    private suspend fun runParse(
        docId: String,
        title: String,
        bytes: ByteArray,
        mime: String,
        resumeFromSidecar: Boolean
    ) = withContext(Dispatchers.IO) {
        val sidecar = sidecarFile(docId)
        var resumedPages: List<String> = emptyList()
        var resumed = false

        if (resumeFromSidecar && sidecar.exists()) {
            resumedPages = readSidecar(sidecar)
            resumed = resumedPages.isNotEmpty()
        }

        PDDocument.load(bytes.inputStream()).use { pd ->
            val total = pd.numberOfPages.coerceAtLeast(1)
            val stripper = PDFTextStripper().apply {
                paragraphStart = "\n\n"
                pageStart = "\n\n"
                // Drop the page-end / page-start line the stripper adds by default;
                // we extract one page at a time and concatenate manually.
                pageEnd = ""
                sortByPosition = true
            }

            val paragraphs = ArrayList<ParsedParagraph>(total.coerceAtMost(2048))
            var charsSoFar = 0
            // Re-emit resumed pages first (fast — already parsed).
            for ((idx, text) in resumedPages.withIndex()) {
                if (!scope.isActive) return@withContext
                val chapter = SentenceSplitter.chapterFromText(text, null)
                for (p in chapter.paragraphs) {
                    if (p.text.isNotBlank()) {
                        paragraphs.add(p)
                        charsSoFar += p.text.length
                    }
                }
                emit(docId, title, paragraphs, idx + 1, total, charsSoFar, resumed)
            }

            // Open the sidecar for append. We only append newly-parsed pages
            // (resumed pages are already in the file).
            val startPage = resumedPages.size + 1
            FileOutputStream(sidecar, true).bufferedWriter().use { writer ->
                for (pageIdx in startPage..total) {
                    if (!scope.isActive) return@withContext
                    stripper.startPage = pageIdx
                    stripper.endPage = pageIdx
                    val text = try {
                        stripper.getText(pd)
                    } catch (t: Throwable) {
                        // A single bad page should not kill the whole parse.
                        "[page $pageIdx could not be extracted: ${t.message}]"
                    }
                    // Persist before emitting so a kill between pages still
                    // leaves a complete sidecar.
                    val line = JSONObject()
                        .put("page", pageIdx)
                        .put("text", text)
                        .toString()
                    writer.write(line)
                    writer.newLine()
                    writer.flush()

                    val chapter = SentenceSplitter.chapterFromText(text, null)
                    for (p in chapter.paragraphs) {
                        if (p.text.isNotBlank()) {
                            paragraphs.add(p)
                            charsSoFar += p.text.length
                        }
                    }
                    emit(docId, title, paragraphs, pageIdx, total, charsSoFar, resumed)
                }
            }

            _state.value = State.Done(
                docId = docId,
                title = title,
                partial = ParsedDocument(
                    title = title,
                    chapters = listOf(ParsedChapter(null, paragraphs.toList())),
                    toc = emptyList()
                ),
                pagesDone = total,
                pagesTotal = total,
                charsSoFar = charsSoFar
            )
            activeJobs.remove(docId)
        }
    }

    private var lastEmitTime = 0L
    private fun emit(
        docId: String,
        title: String,
        paragraphs: List<ParsedParagraph>,
        pagesDone: Int,
        pagesTotal: Int,
        charsSoFar: Int,
        resumed: Boolean
    ) {
        // Throttle to ~12 Hz so 500 pages don't trigger 500 recompositions.
        // First and last emissions are always sent.
        val now = System.currentTimeMillis()
        val isLast = pagesDone == pagesTotal
        if (now - lastEmitTime < 80 && !isLast) return
        lastEmitTime = now
        val partial = ParsedDocument(
            title = title,
            chapters = listOf(ParsedChapter(null, paragraphs.toList())),
            toc = emptyList()
        )
        _state.value = State.Loading(
            docId = docId,
            title = title,
            pagesDone = pagesDone,
            pagesTotal = pagesTotal,
            charsSoFar = charsSoFar,
            partial = partial,
            resumed = resumed
        )
    }

    // --- Sidecar (resume state) ---

    /** Sidecar file path for [docId] — lives next to the document file. */
    fun sidecarFile(docId: String): File {
        val dir = File(ReaderApp.get().filesDir, "pdf-progress").apply { if (!exists()) mkdirs() }
        return File(dir, "$docId.jsonl")
    }

    /** Read all persisted pages from the sidecar. */
    private fun readSidecar(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) null
                    else {
                        try {
                            JSONObject(line).optString("text").takeIf { it.isNotEmpty() }
                        } catch (_: Throwable) { null }
                    }
                }.toList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Remove the sidecar — call after a successful complete parse. */
    fun clearSidecar(docId: String) {
        sidecarFile(docId).delete()
    }

    /** Snapshot of progress for [docId], used by the reader VM when reopening. */
    fun snapshotPagesDone(docId: String): Int {
        val file = sidecarFile(docId)
        if (!file.exists()) return 0
        return readSidecar(file).size
    }

    /**
     * Build a synchronous parse result for callers that don't want streaming
     * (e.g. import-time sentence count). Used by [DocumentRepository] to
     * populate totals without blocking on streaming.
     */
    fun quickTotals(bytes: ByteArray, mime: String, name: String?): Pair<Int, Int> = try {
        PDDocument.load(bytes.inputStream()).use { pd ->
            val pages = pd.numberOfPages
            // Cheap estimate: extract first page only to get a per-page char count,
            // then multiply. Real total comes from the streaming parse.
            val stripper = PDFTextStripper().apply {
                startPage = 1; endPage = 1; pageStart = "\n\n"; pageEnd = ""
            }
            val firstPage = try { stripper.getText(pd) } catch (_: Throwable) { "" }
            pages to firstPage.length.coerceAtLeast(1)
        }
    } catch (_: Throwable) {
        0 to 0
    }

    /** Convenience accessor — true if a parse is in flight for [docId]. */
    fun isActiveFor(docId: String): Boolean =
        activeJobs[docId]?.isActive == true
}
