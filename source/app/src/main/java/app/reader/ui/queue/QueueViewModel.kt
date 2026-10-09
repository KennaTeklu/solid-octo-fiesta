package app.reader.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.data.db.entity.DocumentEntity
import app.reader.data.db.entity.QueueItemEntity
import app.reader.data.repo.DocumentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel : ViewModel() {
    private val repo = DocumentRepository.get()

    data class QueueRow(val item: QueueItemEntity, val doc: DocumentEntity?)

    val items: StateFlow<List<QueueRow>> = combine(repo.observeQueue(), repo.observeRecent()) { queue, docs ->
        val byId = docs.associateBy { it.id }
        queue.map { QueueRow(it, byId[it.docId]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun remove(docId: String) = viewModelScope.launch { repo.removeFromQueue(docId) }
    fun clear() = viewModelScope.launch { repo.clearQueue() }
}
