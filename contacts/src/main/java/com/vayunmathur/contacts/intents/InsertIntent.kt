package com.vayunmathur.contacts.intents

import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class InsertIntent: AssistantIntent<ContactData, Unit>(serializer<ContactData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: ContactData) {
        val details = ContactDetails.empty().copy(
            phoneNumbers = listOf(PhoneNumber(0, input.phoneNumber, CDKPhone.TYPE_MOBILE)),
            names = listOf(Name(0, "", input.name, "", "", ""))
        )
        val contact = Contact(0L, null, null, false, details)
        contact.save(this, contact.details, ContactDetails.empty())
    }
}
