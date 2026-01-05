package com.crypto.signalradar

object SymbolParser {
  private val knownQuotes = listOf("USDT", "BTC", "ETH", "BNB", "BUSD", "FDUSD", "USDC", "EUR", "GBP", "TRY")

  data class ParseResult(
    val symbols: List<String>,
    val ignored: List<String>,
  )

  fun parse(input: String, quote: String): ParseResult {
    val tokens = input.split("\n", ",", " ", "\t")
      .map { it.trim() }
      .filter { it.isNotBlank() }

    val symbols = mutableListOf<String>()
    val ignored = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    for (raw in tokens) {
      var cleaned = raw.uppercase().replace("[^A-Z0-9:/-]".toRegex(), "")
      if (cleaned.isBlank()) {
        continue
      }

      if (cleaned == quote) {
        ignored.add(cleaned)
        continue
      }

      val pair = if (cleaned.contains(":") || cleaned.contains("/") || cleaned.contains("-")) {
        cleaned.replace("[^A-Z0-9]".toRegex(), "")
      } else {
        val matchedQuote = knownQuotes.firstOrNull { cleaned.endsWith(it) && cleaned.length > it.length }
        matchedQuote?.let { cleaned } ?: "${cleaned}${quote}"
      }

      if (pair == quote) {
        ignored.add(cleaned)
        continue
      }

      if (seen.add(pair)) {
        symbols.add(pair)
      }
    }

    return ParseResult(symbols, ignored)
  }
}
