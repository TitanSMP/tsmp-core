package de.titansmp.core.servertransfer

/** Deterministic least-populated backend selection. Backend names are never hardcoded. */
object ServerSelection {
    fun selectLeastPopulated(serverType: String, backends: List<BackendSnapshot>): BackendSnapshot? =
        backends.asSequence()
            .filter { it.serverType == serverType }
            .filter { it.available }
            .filter { it.acceptingPlayers }
            .filter { it.hasCapacity }
            .sortedWith(compareBy<BackendSnapshot> { it.playerCount }.thenBy { it.name })
            .firstOrNull()
}
