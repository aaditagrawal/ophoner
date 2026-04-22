package dev.ophoner.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ophoner.data.model.Conversation
import dev.ophoner.data.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        conversationRepository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }
}
