package app.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.reader.data.db.entity.BookmarkEntity
import app.reader.parser.ParsedChapter
import app.reader.parser.ParsedDocument
import app.reader.parser.ParsedParagraph
import app.reader.parser.PdfStreamingParser
import app.reader.parser.TocEntry
import app.reader.tts.PlaybackState
import app.reader.ui.theme.SentenceHighlight
import app.reader.ui.theme.SentenceHighlightDark
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    docId: String,
    onBack: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenToc: () -> Unit
) {
    val opened by vm.doc.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val autoScroll by vm.autoScroll.collectAsStateWithLifecycle()
    val pdfStream by vm.pdfStream.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }

    // True while a PDF is being parsed page-by-page (for this docId).
    val pdfLoading = pdfStream as? PdfStreamingParser.State.Loading
    val pdfStreamDocId = (pdfStream as? PdfStreamingParser.State.Loading)?.docId
        ?: (pdfStream as? PdfStreamingParser.State.Done)?.docId
    val isParsingThisPdf = pdfLoading != null && pdfStreamDocId == docId

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        opened?.entity?.title ?: "Reading",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { sheet = ReaderSheet.Bookmarks }) {
                        Icon(Icons.Outlined.Bookmarks, contentDescription = "Bookmarks")
                    }
                    IconButton(onClick = { sheet = ReaderSheet.Toc }) {
                        Icon(Icons.Outlined.Toc, contentDescription = "Table of contents")
                    }
                }
            )
        },
        bottomBar = {
            PlaybackBar(
                state = playback,
                pdfLoading = if (isParsingThisPdf) pdfLoading else null,
                onPlayPause = vm::togglePlayPause,
                onPrevSentence = vm::skipPrevSentence,
                onNextSentence = vm::skipNextSentence,
                onPrevParagraph = vm::skipPrevParagraph,
                onNextParagraph = vm::skipNextParagraph,
                onStop = vm::stop
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val parsed = opened?.parsed
            if (parsed == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (parsed.chapters.firstOrNull()?.paragraphs.isNullOrEmpty() && isParsingThisPdf) {
                // First page not ready yet — overlay alone fills the screen.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ReaderBody(
                    parsed = parsed,
                    currentChapter = playback.chapter,
                    currentSentence = playback.sentence,
                    fontScale = fontScale,
                    listState = listState,
                    autoScroll = autoScroll
                )
            }
            // Overlay on top while parsing — reader body remains interactive
            // underneath as soon as the first page is available.
            ProgressOverlay(state = if (isParsingThisPdf) pdfLoading else null)
        }
    }

    val currentSheet = sheet
    if (currentSheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = sheetState
        ) {
            when (currentSheet) {
                ReaderSheet.Bookmarks -> BookmarksSheetContent(
                    bookmarks = bookmarks,
                    onJump = {
                        vm.jumpToBookmark(it)
                        sheet = null
                    },
                    onDelete = vm::deleteBookmark
                )
                ReaderSheet.Toc -> TocSheetContent(
                    toc = opened?.parsed?.toc ?: emptyList(),
                    onJump = {
                        vm.jumpToChapter(it.chapterIndex)
                        sheet = null
                    }
                )
            }
        }
    }
}

private enum class ReaderSheet { Bookmarks, Toc }

@Composable
private fun ReaderBody(
    parsed: ParsedDocument,
    currentChapter: Int,
    currentSentence: Int,
    fontScale: Float,
    listState: LazyListState,
    autoScroll: Boolean
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val highlight = if (isDark) SentenceHighlightDark else SentenceHighlight
    // Compute the absolute flat sentence index that should be centered.
    val targetFlatIndex = remember(parsed, currentChapter, currentSentence) {
        var flat = 0
        for (i in 0 until currentChapter.coerceIn(0, parsed.chapters.lastIndex)) {
            flat += parsed.chapters[i].sentenceCount()
        }
        flat + currentSentence
    }
    LaunchedEffect(targetFlatIndex, autoScroll) {
        if (autoScroll) {
            // Find the paragraph that contains this sentence and scroll to it.
            var seen = 0
            val paragraphIndex = run {
                var idx = 0
                for (ch in parsed.chapters) {
                    for (p in ch.paragraphs) {
                        val count = p.sentences.size
                        if (targetFlatIndex < seen + count) return@run idx
                        seen += count
                        idx++
                    }
                }
                idx
            }
            if (paragraphIndex >= 0) {
                listState.animateScrollToItem(paragraphIndex.coerceAtLeast(0))
            }
        }
    }

    val flatParagraphs = remember(parsed) {
        parsed.chapters.flatMap { it.paragraphs }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // We render a flat list of paragraphs to keep the scrolling UX smooth
        // across chapter boundaries. Chapter titles are emitted as paragraph
        // items with [ParsedParagraph.title] so the reader sees a heading.
        itemsIndexed(flatParagraphs, key = { idx, _ -> idx }) { idx, p ->
            val start = flatParagraphs.subList(0, idx).sumOf { it.sentences.size }
            ParagraphView(
                paragraph = p,
                startSentenceFlat = start,
                activeFlatSentence = targetFlatIndex,
                highlightColor = highlight,
                fontScale = fontScale
            )
        }
    }
}

@Composable
private fun ParagraphView(
    paragraph: ParsedParagraph,
    startSentenceFlat: Int,
    activeFlatSentence: Int,
    highlightColor: Color,
    fontScale: Float
) {
    val text = buildAnnotatedString {
        var cursor = 0
        for ((i, s) in paragraph.sentences.withIndex()) {
            val flat = startSentenceFlat + i
            val isActive = flat == activeFlatSentence
            // Append whitespace before the sentence if needed (the splitter
            // already trimmed, so we join with a single space).
            if (i > 0) append(' ')
            val before = length
            append(s.text)
            if (isActive) {
                addStyle(
                    SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold),
                    start = before,
                    end = length
                )
            }
            cursor = length
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontSize = (18.sp.value * fontScale).sp,
        lineHeight = (28.sp.value * fontScale).sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BookmarksSheetContent(
    bookmarks: List<BookmarkEntity>,
    onJump: (BookmarkEntity) -> Unit,
    onDelete: (Long) -> Unit
) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.padding(16.dp)) {
        Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (bookmarks.isEmpty()) {
            Text("No bookmarks yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                b.excerpt.ifBlank { "Bookmark" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Chapter ${b.chapterIndex + 1} · sentence ${b.sentenceIndex + 1} · ${fmt.format(Date(b.createdAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onJump(b) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Jump")
                        }
                        IconButton(onClick = { onDelete(b.id) }) {
                            Icon(Icons.Outlined.Stop, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocSheetContent(toc: List<TocEntry>, onJump: (TocEntry) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Contents", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (toc.isEmpty()) {
            Text("No table of contents", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(toc, key = { _, t -> "${t.chapterIndex}-${t.title}" }) { _, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .let { it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = { onJump(entry) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Jump")
                        }
                    }
                }
            }
        }
    }
}
