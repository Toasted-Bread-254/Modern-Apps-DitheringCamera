package com.vayunmathur.email.intents

import androidx.core.text.HtmlCompat
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.library.intents.email.EmailData

fun EmailMessage.toEmailData() = EmailData(
    subject = subject,
    from = from,
    to = to,
    date = date,
    body = body?.let { raw ->
        val plain = if (isHtml) HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() else raw
        plain.take(2000)
    },
    isRead = isRead,
)
