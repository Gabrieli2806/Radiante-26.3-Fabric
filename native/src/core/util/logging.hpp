#pragma once

#include <ostream>
#include <string>

namespace radiante {

/**
 * Diagnostics: which pipeline was assembled, what NGX created, how many textures were stitched. On by default (the
 * Debug logging option) so a player's latest.log explains a problem; out() and err() lines both reach it through
 * the Java logger (drainInfo, drainErrors), and errors always do.
 */
void setLoggingEnabled(bool enabled);

bool loggingEnabled();

/** Diagnostics, for the game log and the console, while logging is on; discarded while it is off. */
std::ostream &out();

/** Errors: on stderr while logging is on, and always kept for the game's log (drainErrors). */
std::ostream &err();

/** The error lines written since the last call, one per line. */
std::string drainErrors();

/** The diagnostic lines (out) written since the last call, while logging is on. */
std::string drainInfo();

} // namespace radiante
