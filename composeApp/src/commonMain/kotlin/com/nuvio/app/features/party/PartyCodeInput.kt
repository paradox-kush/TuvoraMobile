package com.nuvio.app.features.party

/** What a join-code field does with typed or pasted text. Pure; shared by phone, iPad, desktop and Apple TV. */
object PartyCode {
    /**
     * The party code in [text], at most six valid characters. A pasted invite link
     * (`https://tuvora.co/party/CODE`, `www.`, `tuvora://party/CODE`, `nuvio://party/CODE`, `?code=CODE`) gives
     * its code; anything else is a typed code, upper-cased with spaces, dashes and invalid characters dropped.
     */
    fun fromInput(text: String): String {
        val raw = CODE_PARAM.find(text)?.groupValues?.get(1)
            ?: LINK.find(text)?.groupValues?.get(1)
            ?: text
        return normalizePartyCode(raw).take(PARTY_CODE_LENGTH)
    }

    // Filtering the whole pasted link character by character kept its letters ("https://…" -> "HTTPST").
    private val LINK = Regex("""(?:(?:www\.)?tuvora\.co/party/|(?:tuvora|nuvio)://party/)([^\s/?#]*)""", RegexOption.IGNORE_CASE)
    private val CODE_PARAM = Regex("""(?:tuvora|nuvio)://party/?\?(?:[^\s#]*&)?code=([^\s&#]*)""", RegexOption.IGNORE_CASE)
}
