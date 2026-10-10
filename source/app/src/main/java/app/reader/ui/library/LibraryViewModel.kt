package app.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reader.data.db.entity.DocumentEntity
import app.reader.data.repo.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel : ViewModel() {

    private val repo = DocumentRepository.get()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val documents: StateFlow<List<DocumentEntity>> = combine(repo.observeRecent(), _query) { docs, q ->
        if (q.isBlank()) docs
        else docs.filter { it.title.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun deleteDocument(id: String) = viewModelScope.launch { repo.deleteDocument(id) }
}
