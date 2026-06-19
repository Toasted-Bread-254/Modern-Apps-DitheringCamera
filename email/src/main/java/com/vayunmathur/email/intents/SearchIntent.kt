package com.vayunmathur.email.intents

import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.serializer

class SearchIntent : AssistantIntent<EmailSearchQuery, List<EmailData>>(serializer<EmailSearchQuery>(), serializer<List<EmailData>>()) {

    override suspend fun performCalculation(input: EmailSearchQuery): List<EmailData> {
        val dao = EmailDatabase.getInstance(this).emailDao()
        return dao.searchMessages(input.query).map { it.toEmailData() }
    }
}
